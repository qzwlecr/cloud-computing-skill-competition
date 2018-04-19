/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package AR

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashMap

/**
  * FP-Tree-Map data structure to privide info for FP-Tree
  * and reduce memory footprint
  */
class FPTreeMap extends Serializable {
  private val records = new HashMap[List[Int], Long]()

  def add(t: List[Int], count: Long): this.type = {
    if (!this.records.contains(t)) {
      this.records(t) = 0
    }
    this.records(t) += count
    this
  }

  def merge(other: FPTreeMap): this.type = {
    other.records.foreach { case (t, count) =>
      if (!this.records.contains(t)) {
        this.records(t) = 0
      }
      this.records(t) += count
    }
    this
  }

  // here to minimize the memory pressure
  def toFPTree: FPTree = {
    val result = new FPTree
    records.foreach { r =>
      result.add(r._1, r._2)
    }
    result
  }
}

/**
  * reverse-FP-Tree data structure used in FP-Growth.
  */
class RFPTree(val validateSuffix: Int => Boolean = _ => true) extends Serializable {

  import RFPTree._

  private val summaries: mutable.HashMap[Int, RSummary] = mutable.HashMap.empty

  // generate a reverse FPSubTree
  // just like a directed rooted tree, with all edge reversed
  def fromFPSubTree(subTree: FPTree.Node, parent: RNode = new RNode(null, -1)): this.type = {
    subTree.children.foreach { case (item, node) =>
      val curr = new RNode(parent, item)
      if (validateSuffix(item)) {
        val summary = summaries.getOrElseUpdate(item, new RSummary)
        summary.nodes += Tuple2(parent, node.count);
      }
      fromFPSubTree(node, curr)
    }
    this
  }

  def extract(minCount: Long): Iterator[(List[Int], Long)] = {
    summaries.iterator.flatMap { case (item, summary) =>
      if (validateSuffix(item)) {
        val totalList: ListBuffer[(List[Int], Long)] = mutable.ListBuffer.empty
        RFPTree.extractHelper(totalList, minCount, List(item), summary.nodes)
        totalList
      }
      else {
        Iterator.empty
      }
    }
  }
}


object RFPTree extends Serializable {

  class RNode(val parent: RNode, val item: Int) extends Serializable {
    def isRoot: Boolean = parent == null
  }

  /** store Item(parent, count) */
  class RSummary extends Serializable {
    val nodes: mutable.ListBuffer[(RNode, Long)] = ListBuffer.empty
  }

  def power[T](rawList: List[T], initial: List[T]) = {
    var res: List[List[T]] = List(initial)
    rawList.foreach {
      item => {
        res = res ::: res.map(item :: _)
      }
    }
    res
  }

  def extractChain(
                    finalTable: ListBuffer[(List[Int], Long)],
                    suffix: List[Int],
                    rnode: RNode,
                    count: Long
                  ): Unit = {
    // TODO for better performance
    val listBuf = new ListBuffer[Int]
    var iter = rnode
    // generate list in reverse order
    while (!iter.isRoot) {
      //      println("fuck", listBuf.toList)
      listBuf += iter.item
      iter = iter.parent
    }
    finalTable ++= power(listBuf.toList, suffix).map(Tuple2(_, count))
  }

  def extractChainExclusive(
                             finalTable: ListBuffer[(List[Int], Long)],
                             suffix: List[Int],
                             rnode: RNode,
                             count: Long
                           ): Unit = {
    if (rnode.parent.isRoot) {
      finalTable += Tuple2(rnode.item :: suffix, count)
    }
    else {
      val listBuf = new ListBuffer[Int]
      var iter = rnode
      // generate list in reverse order
      while (!iter.isRoot) {
        //      println("fuck", listBuf.toList)
        listBuf += iter.item
        iter = iter.parent
      }
      val _ :: tail = power(listBuf.toList, suffix).map(Tuple2(_, count))
      finalTable ++= tail
    }
  }

  def extractHelper(finalTable: ListBuffer[(List[Int], Long)],
                    minCount: Long,
                    suffix: List[Int],
                    nodes: ListBuffer[(RNode, Long)]
                   ): Unit = {
    if (nodes.size == 1) {
      val (rnode, count) = nodes.head
      if (count >= minCount) {
        extractChain(finalTable, suffix, rnode, count)
      }
    }
    else {
      var nullCount = 0L
      var validCount = 0L
      val newNodes: mutable.Map[Int, ListBuffer[(RNode, Long)]] = mutable.Map.empty
      nodes.foreach { case (rnode, count) =>
        if (rnode.isRoot) {
          nullCount += count
        } else {
          validCount += count
          newNodes.getOrElseUpdate(rnode.item, ListBuffer.empty) += Tuple2(rnode, count)
        }
      }
      //    val newNodes = nodes.flatMap{case(rnode, count) =>
      //      if(rnode.isRoot){
      //        nullCount += count
      //        List.empty
      //      }else{
      //        validCount += count
      //        List(Tuple2(rnode, count))
      //      }
      //    }
      if (nullCount + validCount >= minCount) {
        finalTable += Tuple2(suffix, nullCount + validCount)
      }
      if (validCount >= minCount) {
        extractHelperCore(finalTable, minCount, validCount, suffix, newNodes)
      }
    }
  }

  // if it is the core
  // you must have clean input
  // and you have no excuse
  // input: RootNode is not permit
  def extractHelperCore(
                         finalTable: ListBuffer[(List[Int], Long)],
                         minCount: Long,
                         currentCount: Long,
                         suffix: List[Int],
                         // RNode grouped by item
                         nodes: mutable.Map[Int, ListBuffer[(RNode, Long)]]
                       ): Unit = {
    // TODO for better performance
    val peekItem = nodes.keys.max
    val attachTable = new ListBuffer[(RNode, Long)]
    val target = nodes(peekItem).groupBy(_._1).mapValues(_.map(_._2).sum)
    var newCount = currentCount
    if (nodes.size == 1 && target.size == 1) {
      val (rnode, count) = target.head
      if (count >= minCount) {
        extractChainExclusive(finalTable, suffix, rnode, count)
      }
    } else {
      target.foreach {
        case (rnode, count) => {
          if (rnode.item == peekItem) {
            val parent = rnode.parent
            attachTable += Tuple2(rnode.parent, count)
            if (!parent.isRoot) {
              nodes.getOrElseUpdate(parent.item, ListBuffer.empty) += Tuple2(parent, count)
            } else {
              newCount -= count
            }
          }
        }
      }

      nodes -= peekItem
      //    val attachTable = nodes.withFilter(_._1.item == peekItem).map{
      //      // ready for Root node
      //      case (rnode, count) => Tuple2(rnode.parent, count)
      //    }
      //    var discardCount = 0L
      //      // filter out all root node
      //    val discardTable = nodes.flatMap{
      //      case (rnode, count) =>
      //        if(peekItem == rnode.item){
      //          if(rnode.parent.isRoot)
      //            List.empty
      //          else {
      //            discardCount += count
      //            List(Tuple2(rnode.parent, count))
      //          }
      //        }
      //        else {
      //          discardCount += count
      //          List(Tuple2(rnode, count))
      //        }
      //    }.groupBy(_._1).mapValues(_.map(_._2).sum).toList

      extractHelper(finalTable, minCount, peekItem :: suffix, attachTable)
      if(newCount >= minCount){
        extractHelperCore(finalTable, minCount, newCount, suffix, nodes)
      }
    }
  }
}

class FPTree extends Serializable {

  import FPTree._

  val root: Node = new Node(null)

  //  private val summaries: mutable.Map[Int, Summary] = mutable.Map.empty

  def toRFPTree(validateSuffix: Int => Boolean = _ => true): RFPTree = {
    new RFPTree(validateSuffix).fromFPSubTree(root)
  }

  /** Adds a transaction with count. */
  def add(t: Iterable[Int], count: Long = 1L): this.type = {
    require(count > 0)
    var curr = root
    curr.count += count
    t.foreach { item =>
      //      val summary = summaries.getOrElseUpdate(item, new Summary)
      //      summary.count += count
      val child = curr.children.getOrElseUpdate(item, {
        val newNode = new Node(curr)
        newNode.item = item
        //        summary.nodes += newNode
        newNode
      })
      child.count += count
      curr = child
    }
    this
  }

  //  /** Merges another FP-Tree. */
  //  def merge(other: FPTree): this.type = {
  //    other.transactions.foreach { case (t, c) =>
  //      add(t, c)
  //    }
  //    this
  //  }
  //
  //  /** Gets a subtree with the suffix. */
  //  private def project(suffix: Int): FPTree = {
  //    val tree = new FPTree
  //    if (summaries.contains(suffix)) {
  //      val summary = summaries(suffix)
  //      summary.nodes.foreach { node =>
  //        var t = List.empty[Int]
  //        var curr = node.parent
  //        while (!curr.isRoot) {
  //          t = curr.item :: t
  //          curr = curr.parent
  //        }
  //        tree.add(t, node.count)
  //      }
  //    }
  //    tree
  //  }


  //    /** Extracts all patterns with valid suffix and minimum count. */
  def extract(
               minCount: Long,
               validateSuffix: Int => Boolean = _ => true): Iterator[(List[Int], Long)] = {
    //    summaries.iterator.flatMap { case (item, summary) =>
    //      if (validateSuffix(item) && summary.count >= minCount) {
    //        Iterator.single((item :: Nil, summary.count)) ++
    //          project(item).extract(minCount).map { case (t, c) =>
    //            (item :: t, c)
    //          }
    //      } else {
    //        Iterator.empty
    //      }
    //    }
    toRFPTree(validateSuffix).extract(minCount)
  }

  //  /** Returns all transactions in an iterator. */
  //  def transactions: Iterator[(List[Int], Long)] = getTransactions(root)
  //
  //  /** Returns all transactions under this node. */
  //  private def getTransactions(node: Node): Iterator[(List[Int], Long)] = {
  //    var count = node.count
  //    node.children.iterator.flatMap { case (item, child) =>
  //      getTransactions(child).map { case (t, c) =>
  //        count -= c
  //        (item :: t, c)
  //      }
  //    } ++ {
  //      if (count > 0) {
  //        Iterator.single((Nil, count))
  //      } else {
  //        Iterator.empty
  //      }
  //    }
  //  }
}

object FPTree {

  /** Representing a node in an FP-Tree. */
  class Node(val parent: Node) extends Serializable {
    var item: Int = _
    var count: Long = 0L
    val children: mutable.Map[Int, Node] = mutable.Map.empty

    def isRoot: Boolean = parent == null
  }

  /** Summary of an item in an FP-Tree. */
  class Summary extends Serializable {
    var count: Long = 0L
    val nodes: ListBuffer[Node] = ListBuffer.empty
  }

}
