package org.dist.kvstore.gossip.failuredetector

import java.net.Socket

import org.dist.kvstore.gossip.failuredetector.heartbeat.{Peer, PeerProxy}
import org.dist.kvstore.network.{InetAddressAndPort, JsonSerDes, RequestOrResponse, SocketIO}
import org.dist.util.Logging

class Receiver(localIp:InetAddressAndPort, peers:List[Peer], val failureDetector:FailureDetector[Int]) extends Logging {
  val tcpListener = new TcpListener(localIp, requestHandler)

  def requestHandler(requestAndSocket:(RequestOrResponse, SocketIO[RequestOrResponse])):Unit = {
    val response = requestHandler(requestAndSocket._1)
    requestAndSocket._2.write(response)
  }

  def requestHandler(requestOrResponse:RequestOrResponse):RequestOrResponse = {
    if (requestOrResponse.requestId == HeartBeatRequestKeys.HeartBeatRequest) {

      val heartBeatRequest = JsonSerDes.deserialize(requestOrResponse.messageBodyJson, classOf[HeartBeatRequest])
      failureDetector.heartBeatReceived(heartBeatRequest.serverId)

      val response = JsonSerDes.serialize(HeartBeatResponse(true))
      RequestOrResponse(HeartBeatRequestKeys.HeartBeatRequest, response, requestOrResponse.correlationId)
    } else throw new RuntimeException(s"Unknown request id ${requestOrResponse.requestId}")
  }

  def start(): Unit = {
    tcpListener.start()
    failureDetector.start()
  }

  def stop() = {
    tcpListener.stop()
    failureDetector.stop()
  }
}

object HeartBeatRequestKeys {
  val HeartBeatRequest: Short = 0
}

case class HeartBeatRequest(serverId:Int, counter:Int)

case class HeartBeatResponse(success:Boolean)

class Sender(id:Int, peers:List[Peer]) extends Logging {
  var counter = 0
  val client = new Client()
  val peerProxies = peers.map(p ⇒ PeerProxy(p, client, 0, sendHeartBeat))

  def start(): Unit = {
    peerProxies.foreach(p ⇒ p.start())
  }

  def stop() = {
    peerProxies.foreach(p ⇒ p.stop())
  }

  def sendHeartBeat(peerProxy:PeerProxy) = {
    counter = counter + 1
    val appendEntries = JsonSerDes.serialize(HeartBeatRequest(id, counter))
    val request = RequestOrResponse(HeartBeatRequestKeys.HeartBeatRequest, appendEntries, 0)
    val response = client.sendReceive(request, peerProxy.peerInfo.address)
    val heartBeatResponse: HeartBeatResponse = JsonSerDes.deserialize(response.messageBodyJson.getBytes(), classOf[HeartBeatResponse])
    if (heartBeatResponse.success) {
      info(s"Successful in sending heartbeat from ${id} to ${peerProxy.peerInfo.id}")
    } else {
      // TODO: handle term and failures
    }
  }
}


class Client {
  def sendReceive(requestOrResponse: RequestOrResponse, to:InetAddressAndPort):RequestOrResponse = {
    val clientSocket = new Socket(to.address, to.port)
    new SocketIO[RequestOrResponse](clientSocket, classOf[RequestOrResponse], 5000).requestResponse(requestOrResponse)
  }
}

