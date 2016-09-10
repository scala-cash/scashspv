package org.bitcoins.spvnode.models

import akka.actor.{ActorRef, ActorRefFactory, Props}
import org.bitcoins.core.crypto.DoubleSha256Digest
import org.bitcoins.core.protocol.blockchain.BlockHeader
import org.bitcoins.spvnode.constant.Constants
import org.bitcoins.spvnode.modelsd.BlockHeaderTable
import org.bitcoins.spvnode.util.BitcoinSpvNodeUtil
import slick.driver.PostgresDriver.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Created by chris on 9/8/16.
  * This actor is responsible for all databse operations relating to
  * [[BlockHeader]]'s. Currently we store all block headers in a postgresql database
  */
sealed trait BlockHeaderDAO extends CRUDActor[BlockHeader,DoubleSha256Digest] {

  def receive = {
    case createMsg: BlockHeaderDAO.Create =>
      val createdBlockHeader = create(createMsg.blockHeader)
      sendToParent(createdBlockHeader)
    case readMsg: BlockHeaderDAO.Read =>
      val readHeader = read(readMsg.hash)
      sendToParent(readHeader)
    case deleteMsg: BlockHeaderDAO.Delete =>
      val deletedRowCount = delete(deleteMsg.blockHeader)
      sendToParent(deletedRowCount)
  }


  /** Sends a message to our parent actor */
  private def sendToParent(returnMsg: Future[Any]): Unit = returnMsg.onComplete {
    case Success(msg) =>
      context.parent ! msg
    case Failure(exception) =>
      //means the future did not complete successfully, we encountered an error somewhere
      logger.error("Exception: " + exception.toString)
      throw exception
  }

  override val table = TableQuery[BlockHeaderTable]

  def create(blockHeader: BlockHeader): Future[BlockHeader] = {
    val action = (table += blockHeader).andThen(DBIO.successful(blockHeader))
    database.run(action)
  }

  def find(blockHeader: BlockHeader): Query[Table[_],  BlockHeader, Seq] = findByPrimaryKey(blockHeader.hash)

  def findByPrimaryKey(hash : DoubleSha256Digest): Query[Table[_], BlockHeader, Seq] = {
    import ColumnMappers._
    table.filter(_.hash === hash)
  }
}


object BlockHeaderDAO {
  sealed trait BlockHeaderDAOMessage
  case class Create(blockHeader: BlockHeader) extends BlockHeaderDAOMessage
  case class Read(hash: DoubleSha256Digest) extends BlockHeaderDAOMessage
  case class Delete(blockHeader: BlockHeader) extends BlockHeaderDAOMessage

  private case class BlockHeaderDAOImpl() extends BlockHeaderDAO

  def props = Props(BlockHeaderDAOImpl())

  def apply(context: ActorRefFactory): ActorRef = context.actorOf(props,
    BitcoinSpvNodeUtil.createActorName(BlockHeaderDAO.getClass))

  def apply: ActorRef = BlockHeaderDAO(Constants.actorSystem)
}
