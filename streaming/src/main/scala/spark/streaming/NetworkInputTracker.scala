package spark.streaming

import spark.streaming.dstream.{NetworkInputDStream, NetworkReceiver}
import spark.streaming.dstream.{StopReceiver, ReportBlock, ReportError}
import spark.Logging
import spark.SparkEnv
import spark.SparkContext._

import scala.collection.mutable.HashMap
import scala.collection.mutable.Queue

import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import akka.dispatch._

private[streaming] sealed trait NetworkInputTrackerMessage
private[streaming] case class RegisterReceiver(streamId: Int, receiverActor: ActorRef) extends NetworkInputTrackerMessage
private[streaming] case class AddBlocks(streamId: Int, blockIds: Seq[String], metadata: Any) extends NetworkInputTrackerMessage
private[streaming] case class DeregisterReceiver(streamId: Int, msg: String) extends NetworkInputTrackerMessage

/**
 * This class manages the execution of the receivers of NetworkInputDStreams.
 */
private[streaming]
class NetworkInputTracker(
    @transient ssc: StreamingContext,
    @transient networkInputStreams: Array[NetworkInputDStream[_]])
  extends Logging {

  val networkInputStreamMap = Map(networkInputStreams.map(x => (x.id, x)): _*)
  val receiverExecutor = new ReceiverExecutor()
  val receiverInfo = new HashMap[Int, ActorRef]
  val receivedBlockIds = new HashMap[Int, Queue[String]]
  val timeout = 5000.milliseconds

  var currentTime: Time = null

  /** Start the actor and receiver execution thread. */
  def start() {
    ssc.env.actorSystem.actorOf(Props(new NetworkInputTrackerActor), "NetworkInputTracker")
    receiverExecutor.start()
  }

  /** Stop the receiver execution thread. */
  def stop() {
    // TODO: stop the actor as well
    receiverExecutor.interrupt()
    receiverExecutor.stopReceivers()
  }

  /** Return all the blocks received from a receiver. */
  def getBlockIds(receiverId: Int, time: Time): Array[String] = synchronized {
    val queue =  receivedBlockIds.synchronized {
      receivedBlockIds.getOrElse(receiverId, new Queue[String]())
    }
    val result = queue.synchronized {
      queue.dequeueAll(x => true)
    }
    logInfo("Stream " + receiverId + " received " + result.size + " blocks")
    result.toArray
  }

  /** Actor to receive messages from the receivers. */
  private class NetworkInputTrackerActor extends Actor {
    def receive = {
      case RegisterReceiver(streamId, receiverActor) => {
        if (!networkInputStreamMap.contains(streamId)) {
          throw new Exception("Register received for unexpected id " + streamId)
        }
        receiverInfo += ((streamId, receiverActor))
        logInfo("Registered receiver for network stream " + streamId + " from " + sender.path.address)
        sender ! true
      }
      case AddBlocks(streamId, blockIds, metadata) => {
        val tmp = receivedBlockIds.synchronized {
          if (!receivedBlockIds.contains(streamId)) {
            receivedBlockIds += ((streamId, new Queue[String]))
          }
          receivedBlockIds(streamId)
        }
        tmp.synchronized {
          tmp ++= blockIds
        }
        networkInputStreamMap(streamId).addMetadata(metadata)
      }
      case DeregisterReceiver(streamId, msg) => {
        receiverInfo -= streamId
        logError("De-registered receiver for network stream " + streamId
          + " with message " + msg)
        //TODO: Do something about the corresponding NetworkInputDStream
      }
    }
  }

  /** This thread class runs all the receivers on the cluster.  */
  class ReceiverExecutor extends Thread {
    val env = ssc.env

    override def run() {
      try {
        SparkEnv.set(env)
        startReceivers()
      } catch {
        case ie: InterruptedException => logInfo("ReceiverExecutor interrupted")
      } finally {
        stopReceivers()
      }
    }

    /**
     * Get the receivers from the NetworkInputDStreams, distributes them to the
     * worker nodes as a parallel collection, and runs them.
     */
    def startReceivers() {
      val receivers = networkInputStreams.map(nis => {
        val rcvr = nis.getReceiver()
        rcvr.setStreamId(nis.id)
        rcvr
      })

      // Right now, we only honor preferences if all receivers have them
      val hasLocationPreferences = receivers.map(_.getLocationPreference().isDefined).reduce(_ && _)

      // Create the parallel collection of receivers to distributed them on the worker nodes
      val tempRDD =
        if (hasLocationPreferences) {
          val receiversWithPreferences = receivers.map(r => (r, Seq(r.getLocationPreference().toString)))
          ssc.sc.makeRDD[NetworkReceiver[_]](receiversWithPreferences)
        }
        else {
          ssc.sc.makeRDD(receivers, receivers.size)
        }

      // Function to start the receiver on the worker node
      val startReceiver = (iterator: Iterator[NetworkReceiver[_]]) => {
        if (!iterator.hasNext) {
          throw new Exception("Could not start receiver as details not found.")
        }
        iterator.next().start()
      }
      // Run the dummy Spark job to ensure that all slaves have registered.
      // This avoids all the receivers to be scheduled on the same node.
      ssc.sparkContext.makeRDD(1 to 50, 50).map(x => (x, 1)).reduceByKey(_ + _, 20).collect()

      // Distribute the receivers and start them
      ssc.sparkContext.runJob(tempRDD, startReceiver)
    }

    /** Stops the receivers. */
    def stopReceivers() {
      // Signal the receivers to stop
      receiverInfo.values.foreach(_ ! StopReceiver)
    }
  }
}
