package scala.slick.driver

import java.sql.{Statement, PreparedStatement}
import scala.slick.SlickException
import scala.slick.ast.{CompiledStatement, ResultSetMapping, Node}
import scala.slick.lifted.{DDL, Query, Shape, ShapedValue}
import scala.slick.jdbc.{Insert, CompiledMapping, UnitInvoker, UnitInvokerMixin, MutatingStatementInvoker, MutatingUnitInvoker, ResultSetInvoker, PositionedParameters, PositionedResult}
import scala.slick.util.SQLBuilder

trait JdbcInvokerComponent { driver: JdbcDriver =>

  // Create the different invokers -- these methods should be overridden by drivers as needed
  def createCountingInsertInvoker[U](tree: Node) = new CountingInsertInvoker[U](tree)
  def createKeysInsertInvoker[U, RU](tree: Node, keys: Node) = new KeysInsertInvoker[U, RU](tree, keys)
  def createMappedKeysInsertInvoker[U, RU, R](tree: Node, keys: Node, tr: (U, RU) => R) = new MappedKeysInsertInvoker[U, RU, R](tree, keys, tr)
  def createQueryInvoker[R](tree: Node) = new QueryInvoker[R](tree)
  def createUpdateInvoker[T](tree: Node) = new UpdateInvoker[T](tree)
  def createQueryTemplate[P,R](tree: Node): QueryTemplate[P,R] = new QueryTemplate[P,R](tree)

  /** A parameterized query invoker. */
  class QueryTemplate[P, R](protected val tree: Node) extends MutatingStatementInvoker[P, R] {
    protected[this] val ResultSetMapping(_,
      CompiledStatement(_, sres: SQLBuilder.Result, _),
      CompiledMapping(converter, _)) = tree

    def selectStatement = getStatement
    protected def getStatement = sres.sql
    protected def setParam(param: P, st: PreparedStatement): Unit = sres.setter(new PositionedParameters(st), param)
    protected def extractValue(pr: PositionedResult): R = converter.read(pr).asInstanceOf[R]
    protected def updateRowValues(pr: PositionedResult, value: R) = converter.update(value, pr)
    def invoker: this.type = this
  }

  /** Invoker for executing queries. */
  class QueryInvoker[R](tree: Node) extends QueryTemplate[Unit, R](tree)
    with UnitInvokerMixin[R] with MutatingUnitInvoker[R] {
    override protected val delegate = this
  }

  /** Pseudo-invoker for running DDL statements. */
  class DDLInvoker(ddl: DDL) {
    /** Create the entities described by this DDL object */
    def create(implicit session: Backend#Session): Unit = session.withTransaction {
      for(s <- ddl.createStatements)
        session.withPreparedStatement(s)(_.execute)
    }

    /** Drop the entities described by this DDL object */
    def drop(implicit session: Backend#Session): Unit = session.withTransaction {
      for(s <- ddl.dropStatements)
        session.withPreparedStatement(s)(_.execute)
    }

    def ddlInvoker: this.type = this
  }

  /** Pseudo-invoker for running DELETE calls. */
  class DeleteInvoker(protected val tree: Node) {
    protected[this] val ResultSetMapping(_, CompiledStatement(_, sres: SQLBuilder.Result, _), _) = tree

    def deleteStatement = sres.sql

    def delete(implicit session: Backend#Session): Int = session.withPreparedStatement(deleteStatement) { st =>
      sres.setter(new PositionedParameters(st), null)
      st.executeUpdate
    }

    def deleteInvoker: this.type = this
  }

  /** Pseudo-invoker for running INSERT calls. */
  abstract class InsertInvoker[U](tree: Node) {

    protected[this] val ResultSetMapping(_, insertNode: Insert, CompiledMapping(converter, _)) = tree
    protected[this] lazy val builder = createInsertBuilder(insertNode)

    type RetOne
    type RetMany

    protected def retOne(st: Statement, value: U, updateCount: Int): RetOne
    protected def retMany(values: Seq[U], individual: Seq[RetOne]): RetMany
    protected def retManyBatch(st: Statement, values: Seq[U], updateCounts: Array[Int]): RetMany

    protected lazy val insertResult = builder.buildInsert
    lazy val insertStatement = insertResult.sql
    def insertStatementFor[TT](query: Query[TT, U]): String = builder.buildInsert(query).sql
    def insertStatementFor[TT](c: TT)(implicit shape: Shape[TT, U, _]): String = insertStatementFor(Query(c)(shape))

    def useBatchUpdates(implicit session: Backend#Session) = session.capabilities.supportsBatchUpdates

    protected def prepared[T](sql: String)(f: PreparedStatement => T)(implicit session: Backend#Session) =
      session.withPreparedStatement(sql)(f)

    /** Insert a single row. */
    def insert(value: U)(implicit session: Backend#Session): RetOne = prepared(insertStatement) { st =>
      st.clearParameters()
      converter.set(value, new PositionedParameters(st))
      val count = st.executeUpdate()
      retOne(st, value, count)
    }

    /** Insert multiple rows. Uses JDBC's batch update feature if supported by
      * the JDBC driver. Returns Some(rowsAffected), or None if the database
      * returned no row count for some part of the batch. If any part of the
      * batch fails, an exception is thrown. */
    def insertAll(values: U*)(implicit session: Backend#Session): RetMany = session.withTransaction {
      if(!useBatchUpdates || (values.isInstanceOf[IndexedSeq[_]] && values.length < 2)) {
        retMany(values, values.map(insert))
      } else {
        prepared(insertStatement) { st =>
          st.clearParameters()
          for(value <- values) {
            converter.set(value, new PositionedParameters(st))
            st.addBatch()
          }
          var unknown = false
          val counts = st.executeBatch()
          retManyBatch(st, values, counts)
        }
      }
    }

    def insertInvoker: this.type = this
  }

  /** An InsertInvoker that can also insert from another query. */
  trait FullInsertInvoker[U] { this: InsertInvoker[U] =>
    type RetQuery

    protected def retQuery(st: Statement, updateCount: Int): RetQuery

    def insertExpr[TT](c: TT)(implicit shape: Shape[TT, U, _], session: Backend#Session): RetQuery =
      insert(Query(c)(shape))(session)

    def insert[TT](query: Query[TT, U])(implicit session: Backend#Session): RetQuery = {
      val sbr = builder.buildInsert(query)
      prepared(insertStatementFor(query)) { st =>
        st.clearParameters()
        sbr.setter(new PositionedParameters(st), null)
        val count = st.executeUpdate()
        retQuery(st, count)
      }
    }
  }

  /** Pseudo-invoker for running INSERT calls and returning affected row counts. */
  class CountingInsertInvoker[U](tree: Node) extends InsertInvoker[U](tree) with FullInsertInvoker[U] {

    type RetOne = Int
    type RetMany = Option[Int]
    type RetQuery = Int

    protected def retOne(st: Statement, value: U, updateCount: Int) = updateCount

    protected def retMany(values: Seq[U], individual: Seq[RetOne]) = Some(individual.sum)

    protected def retManyBatch(st: Statement, values: Seq[U], updateCounts: Array[Int]) = {
      var unknown = false
      var count = 0
      for((res, idx) <- updateCounts.zipWithIndex) res match {
        case Statement.SUCCESS_NO_INFO => unknown = true
        case Statement.EXECUTE_FAILED =>
          throw new SlickException("Failed to insert row #" + (idx+1))
        case i => count += i
      }
      if(unknown) None else Some(count)
    }

    protected def retQuery(st: Statement, updateCount: Int) = updateCount

    def returning[RT, RU](value: RT)(implicit shape: Shape[RT, RU, _]) =
      createKeysInsertInvoker[U, RU](tree, new ShapedValue[RT, RU](value, shape).packedNode)
  }

  /** Base class with common functionality for KeysInsertInvoker and MappedKeysInsertInvoker. */
  abstract class AbstractKeysInsertInvoker[U, RU](tree: Node, keys: Node)
    extends InsertInvoker[U](tree) {

    protected def buildKeysResult(st: Statement): UnitInvoker[RU] =
      ResultSetInvoker[RU](_ => st.getGeneratedKeys)(pr => keyConverter.read(pr).asInstanceOf[RU])

    // Returning keys from batch inserts is generally not supported
    override def useBatchUpdates(implicit session: Backend#Session) = false

    protected lazy val (keyColumns, keyConverter) =
      builder.buildReturnColumns(keys, insertResult.table)

    override protected def prepared[T](sql: String)(f: PreparedStatement => T)(implicit session: Backend#Session) =
      session.withPreparedInsertStatement(sql, keyColumns.toArray)(f)
  }

  /** Pseudo-invoker for running INSERT calls and returning generated keys. */
  class KeysInsertInvoker[U, RU](tree: Node, keys: Node)
    extends AbstractKeysInsertInvoker[U, RU](tree, keys) with FullInsertInvoker[U] {

    type RetOne = RU
    type RetMany = Seq[RU]
    type RetQuery = RetMany

    protected def retOne(st: Statement, value: U, updateCount: Int) =
      buildKeysResult(st).first()(null)

    protected def retMany(values: Seq[U], individual: Seq[RetOne]) = individual

    protected def retManyBatch(st: Statement, values: Seq[U], updateCounts: Array[Int]) = {
      implicit val session: Backend#Session = null
      buildKeysResult(st).to[Vector]
    }

    protected def retQuery(st: Statement, updateCount: Int) = {
      implicit val session: Backend#Session = null
      buildKeysResult(st).to[Vector]
    }

    def into[R](f: (U, RU) => R) = createMappedKeysInsertInvoker[U, RU, R](tree, keys, f)
  }

  /** Pseudo-invoker for running INSERT calls and returning generated keys combined with the values. */
  class MappedKeysInsertInvoker[U, RU, R](tree: Node, keys: Node, tr: (U, RU) => R)
    extends AbstractKeysInsertInvoker[U, RU](tree, keys) {

    type RetOne = R
    type RetMany = Seq[R]

    protected def retOne(st: Statement, value: U, updateCount: Int) = {
      val ru = buildKeysResult(st).first()(null)
      tr(value, ru)
    }

    protected def retMany(values: Seq[U], individual: Seq[RetOne]) = individual

    protected def retManyBatch(st: Statement, values: Seq[U], updateCounts: Array[Int]) = {
      implicit val session: Backend#Session = null
      val ru = buildKeysResult(st).to[Vector]
      (values, ru).zipped.map(tr)
    }
  }

  /** Pseudo-invoker for running UPDATE calls. */
  class UpdateInvoker[T](protected val tree: Node) {
    protected[this] val ResultSetMapping(_,
      CompiledStatement(_, sres: SQLBuilder.Result, _),
      CompiledMapping(converter, _)) = tree

    def updateStatement = getStatement

    protected def getStatement = sres.sql

    def update(value: T)(implicit session: Backend#Session): Int = session.withPreparedStatement(updateStatement) { st =>
      st.clearParameters
      val pp = new PositionedParameters(st)
      converter.set(value, pp)
      sres.setter(pp, null)
      st.executeUpdate
    }

    def updateInvoker: this.type = this
  }
}
