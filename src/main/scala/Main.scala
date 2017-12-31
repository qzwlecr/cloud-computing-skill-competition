import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
package AR {

  object Main {
    def main(args: Array[String]): Unit = {
      val fileInput = args(0)
      val fileOutput = args(1)
      val fileTemp = args(2)
      val conf = new SparkConf().setAppName("Association Rules")
        .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .set("spark.network.timeout","3000")
      val sc = new SparkContext(conf)
      val originData = sc.textFile(fileInput + "/D.dat", 1800)

      val transactions: RDD[Array[Int]] = originData.map(s => s.trim.split(' ').map(x => x.toInt)).cache()
      val model = new FPGrowth().setMinSupport(0.092).setNumPartitions(900).run(transactions)
      model.freqItemsets.map(x=>x.items.reverse).sortBy(x => x.toString).saveAsTextFile(fileOutput+"/D.dat")
      //val answerData = sc.textFile(fileInput + "/D-answer.dat").map(x=>x.trim.split(" ").map(x=>x.toInt)).map(x=>(x.take(x.length-1),x.last))
      //val freqItemss = sc.textFile(fileInput + "/freq.dat").collect().map(x=>x.trim.split(" ").map(x=>x.toInt))
      //val freqItems = freqItemss(0)
      val itemsWithFreq = model.freqItemsets.map(x => (x.items.toList, x.freq)).collect()
      val itemsWithFreqMap = itemsWithFreq.toMap

      var root: RulesTree = RuleNode(0, 0.0, Nil)
      for ((items, son) <- itemsWithFreq) {
        if (items.length > 1) {
          items.foreach(
            x => {
              val mother = items diff List(x)
              root = root.insert(mother, x, 1.0 * son / itemsWithFreqMap(mother))
            }
          )
        }
      }

      val tree = sc.broadcast(root)
      val userData = sc.textFile(fileInput + "/U.dat", 300)
      val users = userData.map(s => s.trim.split(' ').map(x => x.toInt)).map(x => x.intersect(model.freqItems))
      users.map(x => tree.value.find(x.toSet)._1).saveAsTextFile(fileOutput + "/U.dat")

    }
  }

}
