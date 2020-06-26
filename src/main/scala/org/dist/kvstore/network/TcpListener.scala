package org.dist.kvstore.network

import java.net.{InetSocketAddress, ServerSocket}

import org.dist.kvstore.gossip.Gossiper
import org.dist.kvstore.gossip.handlers.{GossipDigestAck2Handler, GossipDigestSynAckHandler, GossipDigestSynHandler, ReadMessageHandler, RowMutationHandler}
import org.dist.kvstore.gossip.messages.GossipDigestSyn
import org.dist.kvstore.StorageService
import org.dist.util.Utils
import org.slf4j.LoggerFactory

class TcpListener(localEp: InetAddressAndPort, gossiper: Gossiper, storageService: StorageService, messagingService: MessagingService) extends Thread {
  private val logger = LoggerFactory.getLogger(classOf[TcpListener])

  val serverSocket = new ServerSocket()
  @volatile var isRunning = false

  override def run(): Unit = {
    isRunning = true
    serverSocket.bind(new InetSocketAddress(localEp.address, localEp.port))

    logger.info(s"Listening on ${localEp}")

    while (isRunning) {
      val socket = serverSocket.accept()
      val message = new SocketIO[Message](socket, classOf[Message], 5000).read()
      logger.debug(s"Got message ${message}")

      if (message.header.verb == Verb.GOSSIP_DIGEST_SYN) {
        val gossipDigestSyn = JsonSerDes.deserialize(message.payloadJson.getBytes, classOf[GossipDigestSyn])
        val synAckMessage = new GossipDigestSynHandler(gossiper).handleMessage(gossipDigestSyn)
        messagingService.sendTcpOneWay(synAckMessage, message.header.from)

      } else if (message.header.verb == Verb.GOSSIP_DIGEST_ACK) {
        new GossipDigestSynAckHandler(gossiper, messagingService).handleMessage(message)

      } else if (message.header.verb == Verb.GOSSIP_DIGEST_ACK2) {
        new GossipDigestAck2Handler(gossiper, messagingService).handleMessage(message)

      } else if(message.header.verb == Verb.RESPONSE) {

        val handler = messagingService.getHandler(message.header.id)
        if (handler != null) handler.response(message)

      } else if (message.header.verb == Verb.ROW_MUTATION) {
        new RowMutationHandler(localEp, storageService, messagingService).handleMessage(message)

      } else if (message.header.verb == Verb.GET_CF) {
        new ReadMessageHandler(localEp, storageService, messagingService).handleMessage(message)
      }
    }
  }

  def shutdown(): Unit = {
    Utils.swallow(serverSocket.close())
    isRunning = false
  }

}
