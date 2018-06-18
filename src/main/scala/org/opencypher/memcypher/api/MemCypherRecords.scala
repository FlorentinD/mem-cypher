/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencypher.memcypher.api

import org.opencypher.memcypher.impl.MemRuntimeContext
import org.opencypher.memcypher.impl.value.CypherMapOps._
import org.opencypher.memcypher.impl.value.CypherValueOps._
import org.opencypher.okapi.api.table.{CypherRecords, CypherRecordsCompanion}
import org.opencypher.okapi.api.types.CypherType
import org.opencypher.okapi.api.types.CypherType._
import org.opencypher.okapi.api.value.CypherValue.{CypherInteger, CypherList, CypherMap, CypherValue}
import org.opencypher.okapi.impl.exception.NotImplementedException
import org.opencypher.okapi.impl.table.RecordsPrinter
import org.opencypher.okapi.impl.util.PrintOptions
import org.opencypher.okapi.ir.api.block.{Asc, Desc, SortItem}
import org.opencypher.okapi.ir.api.expr._
import org.opencypher.okapi.relational.impl.table.RecordHeader
import org.opencypher.memcypher.impl.table.RecordHeaderUtils._
import org.opencypher.okapi.api.value.CypherValue

object MemRecords extends CypherRecordsCompanion[MemRecords, MemCypherSession] {

  def create(rows: Seq[CypherMap], header: RecordHeader): MemRecords = MemRecords(Embeddings(rows), header)

  def create(embeddings: Embeddings, header: RecordHeader): MemRecords = MemRecords(embeddings, header)

  override def unit()(implicit session: MemCypherSession): MemRecords = MemRecords(Embeddings.unit, RecordHeader.empty)
}

case class MemRecords(
  data: Embeddings,
  header: RecordHeader) extends CypherRecords {

  override def rows: Iterator[String => CypherValue] = data.rows.map(_.value)

  override def columns: Seq[String] = header.fieldsInOrder

  override def columnType: Map[String, CypherType] = data.data.headOption match {
    case Some(row) => row.value.mapValues(_.cypherType)
    case None => Map.empty
  }

  override def iterator: Iterator[CypherMap] = toCypherMap.iterator

  override def size: Long = rows.size

  override def show(implicit options: PrintOptions): Unit = RecordsPrinter.print(this)

  override def collect: Array[CypherMap] = iterator.toArray

  def toCypherMap: Seq[CypherMap] = {
    data.data.map(row => row.nest(header))
  }
}

object Embeddings {
  def empty: Embeddings = Embeddings(List.empty)
  def unit: Embeddings = Embeddings(List(CypherMap()))
}

case class Embeddings(data: Seq[CypherMap]) {

  def columns: Set[String] = data.headOption match {
    case Some(row) => row.keys
    case None => Set.empty
  }

  def rows: Iterator[CypherMap] = data.iterator

  // ---------------
  // Unary operators
  // ---------------

  def select(fields: Set[String])(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings =
    copy(data = data.map(row => row.filterKeys(fields)))

  def project(expr: Expr, toKey: String)(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings =
    copy(data = data.map(row => row.updated(toKey, row.evaluate(expr))))

  def filter(expr: Expr)(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings =
    copy(data = data.filter(row => row.evaluate(expr).as[Boolean].getOrElse(false)))

  def drop(fields: Set[String])(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings =
    copy(data = data.map(row => row.filterKeys(row.keys -- fields)))

  def distinct(fields: Set[String])(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings =
    copy(data = select(fields)(header, context).data.distinct)

  def group(by: Set[Expr], aggregations: Set[(Var, Aggregator)])(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings = {
    val groupKeys = by.toSeq
    val groupedData = data
      .groupBy(row => groupKeys.map(row.evaluate))

    val withAggregates = groupedData.mapValues {
      values =>
        aggregations.foldLeft(CypherMap.empty) {
          case (current, (Var(to), agg)) =>
            agg match {
              case Count(inner, distinct) =>
                val evaluated = values
                  .map(_.evaluate(inner))
                  .filterNot(_.isNull)
                val toCount = if (distinct) evaluated.distinct else evaluated
                current.updated(to, CypherInteger(toCount.size))

              case CountStar(_) =>
                current.updated(to, CypherInteger(values.size))

              case Sum(inner) =>
                val sum = values
                  .map(_.evaluate(inner))
                  .filterNot(_.isNull)
                  .reduce(_ + _)
                current.updated(to, sum)

              case Min(inner) =>
                val min = values
                  .map(_.evaluate(inner))
                  .sortWith(_ < _)
                  .head
                current.updated(to, min)

              case Max(inner) =>
                val max = values
                  .map(_.evaluate(inner))
                  .sortWith(_ > _)
                  .head
                current.updated(to, max)

              case Collect(inner, distinct) =>
                val coll = values
                  .map(_.evaluate(inner))
                  .filterNot(_.isNull)
                val toCollect = if (distinct) coll.distinct else coll
                current.updated(to, CypherValue.apply(toCollect)) //CypherList(toCollect) would return List(List(...))

              case other => throw NotImplementedException(s"Aggregation $other not yet supported")
            }
        }
    }

    val groupCols = groupKeys.map(_.columnName)

    val withKeysAndAggregates = withAggregates.map {
      case (groupValues, aggregateMap) =>
        groupCols.zip(groupValues).foldLeft(aggregateMap) {
          case (current, (groupCol, groupValue)) => current.updated(groupCol, groupValue)
        }
    }.toList

    copy(data = withKeysAndAggregates)
  }

  def orderBy(sortItems: Seq[SortItem[Expr]])(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings = {
    val newData = sortItems.foldLeft(data) {
      case (currentData, sortItem) =>
        sortItem match {
          case Asc(inner) =>
            currentData.sortWith((l, r) => l.evaluate(inner) < r.evaluate(inner))

          case Desc(inner) =>
            currentData.sortWith((l, r) => l.evaluate(inner) > r.evaluate(inner))
        }
    }
    copy(data = newData)
  }

  // ----------------
  // Binary operators
  // ----------------

  def innerJoin(other: Embeddings, left: Expr, right: Expr)(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings = {
    if (this.data.size < other.data.size) {
      join(other, left, right, rightOuter = false)
    } else {
      other.join(this, right, left, rightOuter = false)
    }
  }

  def rightOuterJoin(other: Embeddings, left: Expr, right: Expr)(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings =
    join(other, left, right, rightOuter = true)

  private def join(
    other: Embeddings,
    left: Expr,
    right: Expr,
    rightOuter: Boolean)(implicit header: RecordHeader, context: MemRuntimeContext): Embeddings = {

    val hashTable = this.rows.map(row => row.evaluate(left).hashCode() -> row)
      .toSeq
      .groupBy(_._1)

    val newData = other.rows
      .filter(rightRow => rightOuter || hashTable.contains(rightRow.evaluate(right).hashCode()))
      .flatMap(rightRow => {
        val rightValue = rightRow.evaluate(right)
        hashTable.get(rightValue.hashCode()) match {
          case Some(leftValues) => leftValues
            .map(_._2)
            .filter(leftRow => leftRow.evaluate(left) == rightValue) // hash collision check
            .map(leftRow => leftRow ++ rightRow)
          case None if rightOuter => Seq(rightRow)
          case None => Seq.empty[CypherMap]
        }
      }).toList

    copy(data = newData)
  }

  def cartesianProduct(other: Embeddings): Embeddings = {
    val newData = for {
      left <- data
      right <- other.data
    } yield left ++ right

    copy(data = newData)
  }

  def unionAll(other: Embeddings): Embeddings = copy(data ++ other.data)
  
  // --------------
  // Helper methods
  // --------------

  def withColumnsRenamed(renamings: Map[String, String]): Embeddings = {
    val newData = data.map(row => row.keys.foldLeft(CypherMap.empty) {
      case (currentRow, key) =>
        val newKey = renamings.getOrElse(key, key)
        currentRow.updated(newKey, row(key))
    })
    copy(data = newData)
  }
}
