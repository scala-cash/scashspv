package org.bitcoins.spvnode.bloom

import org.bitcoins.core.crypto.{DoubleSha256Digest, HashDigest, Sha256Hash160Digest}
import org.bitcoins.core.number.{UInt32, UInt64}
import org.bitcoins.core.protocol.transaction.{Transaction, TransactionOutPoint}
import org.bitcoins.core.protocol.{CompactSizeUInt, NetworkElement}
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.util.{BitcoinSLogger, BitcoinSUtil, Factory, NumberUtil}
import org.bitcoins.spvnode.serializers.control.RawBloomFilterSerializer

import scala.annotation.tailrec
import scala.util.hashing.MurmurHash3

/**
  * Created by chris on 8/2/16.
  */
sealed trait BloomFilter extends NetworkElement with BitcoinSLogger {

  /** How large the bloom filter is, in Bytes */
  def filterSize: CompactSizeUInt

  /** The bits that are set inside of the bloom filter */
  def data: Seq[Byte]

  /** The number of hash functions used in the bloom filter */
  def hashFuncs: UInt32

  /** An arbitrary value to add to the seed value in the hash function used by the bloom filter. */
  def tweak: UInt32

  /** A set of flags that control how outpoints corresponding to a matched pubkey script are added to the filter.
    * See the 'Comparing Transaction Elements to a Bloom Filter' section in this link
    * https://bitcoin.org/en/developer-reference#filterload
    */
  def flags: BloomFlag

  /** Inserts a sequence of bytes into the [[BloomFilter]] */
  def insert(bytes: Seq[Byte]): BloomFilter = {
    //these are the bit indexes that need to be set inside of data
    val bitIndexes = (0 until hashFuncs.toInt).map(i => murmurHash(i,bytes))
    logger.debug("Bitindexes that need to be set: " + bitIndexes)
    @tailrec
    def loop(remainingBitIndexes: Seq[Int], accum: Seq[Byte]): Seq[Byte] = {
      if (remainingBitIndexes.isEmpty) accum
      else {
        val currentIndex = remainingBitIndexes.head
        //since we are dealing with a bit vector, this gets the byteIndex we need to set
        //the bit inside of.
        val byteIndex = currentIndex >>> 3
        //we need to calculate the bitIndex we need to set inside of our byte
        val bitIndex = (1 << (7 & currentIndex)).toByte
        val byte = accum(byteIndex)
        val setBitByte: Byte = (byte | bitIndex ).toByte
        //replace old byte with new byte with bit set
        val newAccum: Seq[Byte] = accum.updated(byteIndex,setBitByte)
        loop(remainingBitIndexes.tail,newAccum)
      }
    }
    val newData = loop(bitIndexes,data)
    BloomFilter(filterSize,newData,hashFuncs,tweak,flags)
  }

  /** Inserts a [[HashDigest]] into [[data]] */
  def insert(hash: HashDigest): BloomFilter = insert(hash.bytes)

  /** Inserts a sequence of [[HashDigest]]'s into our BloomFilter */
  def insertHashes(hashes: Seq[HashDigest]): BloomFilter = {
    val byteVectors = hashes.map(_.bytes)
    insertByteVectors(byteVectors)
  }

  /** Inserts a [[TransactionOutPoint]] into [[data]] */
  def insert(outPoint: TransactionOutPoint): BloomFilter = insert(outPoint.bytes)

  /** Checks if [[data]] contains the given sequence of bytes */
  def contains(bytes: Seq[Byte]): Boolean = {
    val bitIndexes = (0 until hashFuncs.toInt).map(i => murmurHash(i,bytes))
    @tailrec
    def loop(remainingBitIndexes: Seq[Int], accum: Seq[Boolean]): Boolean = {
      if (remainingBitIndexes.isEmpty) !accum.exists(_ == false)
      else {
        val currentIndex = remainingBitIndexes.head
        val byteIndex = currentIndex >>> 3
        val bitIndex = (1 << (7 & currentIndex)).toByte
        val byte = data(byteIndex)
        val isBitSet = (byte & bitIndex) != 0
        loop(remainingBitIndexes.tail, isBitSet +: accum)
      }
    }
    loop(bitIndexes,Seq())
  }

  /** Checks if [[data]] contains a [[DoubleSha256Digest]] */
  def contains(hash: DoubleSha256Digest): Boolean = contains(hash.bytes)

  /** Checks if [[data]] contains a [[TransactionOutPoint]] */
  def contains(outPoint: TransactionOutPoint): Boolean = contains(outPoint.bytes)

  /** Checks if [[data]] contains a [[Sha256Hash160Digest]] */
  def contains(hash: Sha256Hash160Digest): Boolean = contains(hash.bytes)

  /**
    * Checks if the given [[Transaction]] matches inside of our bloom filter
    * Also adds the transaction's scriptPubKey's to the BloomFilter as outPoints
    * so that if another transaction attempts to spend the given transaction it will match the filter
    * https://github.com/bitcoin/bitcoin/blob/master/src/test/bloom_tests.cpp#L114
    */
  def isRelevantAndUpdate(transaction: Transaction): Boolean = {
    val containsTxId = contains(transaction.txId)
    val scriptPubKeys = transaction.outputs.map(_.scriptPubKey)
    //pull out all of the constants in the scriptPubKey's
    val constantsWithOuputIndex = scriptPubKeys.zipWithIndex.map { case (scriptPubKey, index) =>
      val constants = scriptPubKey.asm.filterNot(_.isInstanceOf[ScriptConstant])
      constants.map(c => (c,index))
    }.flatten

    //if the constant is contained in our BloomFilter, we need to add this txs outPoint to the bloom filter
    val constants = constantsWithOuputIndex.filterNot {
      case (c,index) => contains(c.bytes)
    }

    val outPointsThatNeedToBeInserted = constants.map {
      case (_,index) => TransactionOutPoint(transaction.txId,UInt32(index)).bytes
    }
    val newFilter = insertByteVectors(outPointsThatNeedToBeInserted)
    containsTxId || outPointsThatNeedToBeInserted.nonEmpty
  }


  /** Checks if the transaction's txid, or any of the constant's in it's scriptPubKeys/scriptSigs match our BloomFilter */
  def isRelevant(transaction: Transaction): Boolean = {
    val scriptPubKeys = transaction.outputs.map(_.scriptPubKey)
    //pull out all of the constants in the scriptPubKey's
    val constantsWithOuputIndex = scriptPubKeys.zipWithIndex.flatMap { case (scriptPubKey, index) =>
      val constants = scriptPubKey.asm.filter(_.isInstanceOf[ScriptConstant])
      constants.map(c => (c,index))
    }

    //check if the bloom filter contains any of the script constants in our outputs
    val constantsOutput = constantsWithOuputIndex.filter {
      case (c,index) => contains(c.bytes)
    }

    val scriptSigs = transaction.inputs.map(_.scriptSignature)
    val constantsWithInputIndex = scriptSigs.zipWithIndex.flatMap { case (scriptSig, index) =>
      val constants = scriptSig.asm.filter(_.isInstanceOf[ScriptConstant])
      constants.map(c => (c,index))
    }
    //check if the filter contains any of the prevouts in this tx
    val containsOutPoint = transaction.inputs.filter(i => contains(i.previousOutput))

    //check if the bloom filter contains any of the script constants in our inputs
    val constantsInput = constantsWithInputIndex.filter {
      case (c, index) =>
        logger.debug("Checking input constant: " + c)
        contains(c.bytes)
    }

    constantsOutput.nonEmpty || constantsInput.nonEmpty ||
      containsOutPoint.nonEmpty || contains(transaction.txId)
  }

  /** Updates this bloom filter to contain the relevant information for the given Transaction */
  def update(transaction: Transaction): BloomFilter = {
    val scriptPubKeys = transaction.outputs.map(_.scriptPubKey)
    //a sequence of outPoints that need to be inserted into the filter
    val outPoints: Seq[TransactionOutPoint] = scriptPubKeys.zipWithIndex.flatMap {
      case (scriptPubKey,index) =>
        //constants that matched inside of our current filter
        val constants = scriptPubKey.asm.filter(c => c.isInstanceOf[ScriptConstant] && contains(c.bytes))
        //we need to create a new outpoint in the filter if a constant in the scriptPubKey matched
        constants.map(c => TransactionOutPoint(transaction.txId,UInt32(index)))
    }

    logger.debug("Inserting outPoints: " + outPoints)
    val outPointsBytes = outPoints.map(_.bytes)
    val filterWithOutPoints = insertByteVectors(outPointsBytes)
    //add txid
    val filterWithTxIdAndOutPoints = filterWithOutPoints.insert(transaction.txId)
    filterWithTxIdAndOutPoints
  }

  /**
    * Performs the [[MurmurHash3]] on the given hash
    *
    * @param hashNum the nth hash function we are using
    * @param bytes the bytes of the data that needs to be inserted into the [[BloomFilter]]
    * @return the index of the bit inside of [[data]] that needs to be set to 1
    */
  private def murmurHash(hashNum: Int, bytes: Seq[Byte]): Int = {
    //TODO: The call of .toInt is probably the source of a bug here, need to come back and look at this
    //since this isn't consensus critical though I'm leaving this for now
    val seed = (hashNum * murmurConstant.underlying + tweak.underlying).toInt
    val murmurHash = MurmurHash3.bytesHash(bytes.toArray, seed)
    val uint32 = UInt32(BitcoinSUtil.encodeHex(murmurHash))
    val modded = uint32.underlying % (filterSize.num.toInt * 8)
    //remove sign bit
    modded.toInt
  }

  /** See BIP37 to see where this number comes from https://github.com/bitcoin/bips/blob/master/bip-0037.mediawiki#bloom-filter-format */
  private def murmurConstant = UInt32("fba4c795")

  /** Adds a sequence of byte vectors to our bloom filter then returns that new filter*/
   def insertByteVectors(bytes: Seq[Seq[Byte]]): BloomFilter = {
    @tailrec
    def loop(remainingByteVectors: Seq[Seq[Byte]], accumBloomFilter: BloomFilter): BloomFilter = {
      if (remainingByteVectors.isEmpty) accumBloomFilter
      else loop(remainingByteVectors.tail,accumBloomFilter.insert(remainingByteVectors.head))
    }
    loop(bytes,this)
  }

  override def hex = RawBloomFilterSerializer.write(this)
}


object BloomFilter extends Factory[BloomFilter] {

  private case class BloomFilterImpl(filterSize: CompactSizeUInt, data: Seq[Byte], hashFuncs : UInt32,
                                     tweak: UInt32, flags: BloomFlag) extends BloomFilter
  /** Max bloom filter size as per https://bitcoin.org/en/developer-reference#filterload */
  val maxSize = UInt32(36000)

  /** Max hashFunc size as per https://bitcoin.org/en/developer-reference#filterload */
  val maxHashFuncs = UInt32(50)


  def apply(numElements: Int, falsePositiveRate: Double, tweak: UInt32, flags: BloomFlag): BloomFilter = {
    import scala.math._
    //m = number of bits in the array
    //n = number of elements in the array
    //from https://github.com/bitcoin/bips/blob/master/bip-0037.mediawiki#bloom-filter-format
    val optimalFilterSize : Double = (-1 / pow(log(2),2) * numElements * log(falsePositiveRate)) / 8
    logger.debug("optimalFilterSize " + optimalFilterSize)
    //BIP37 places limitations on the filter size, namely it cannot be > 36,000 bytes
    val actualFilterSize: Int = max(1,min(optimalFilterSize, maxSize.underlying * 8)).toInt
    logger.debug("actualFilterSize: " + actualFilterSize)
    val optimalHashFuncs: Double = (actualFilterSize * 8 / numElements * log(2))
    //BIP37 places a limit on the amount of hashFuncs we can use, which is 50
    val actualHashFuncs: Int = max(1,min(optimalHashFuncs, maxHashFuncs.underlying)).toInt

    val emptyByteArray = Seq.fill(actualFilterSize)(0.toByte)
    BloomFilter(CompactSizeUInt(UInt64(actualFilterSize)), emptyByteArray, UInt32(actualHashFuncs), tweak, flags)
  }

  def apply(filterSize: CompactSizeUInt, data: Seq[Byte], hashFuncs: UInt32, tweak: UInt32, flags: BloomFlag): BloomFilter = {
    BloomFilterImpl(filterSize, data, hashFuncs, tweak, flags)
  }


  override def fromBytes(bytes: Seq[Byte]): BloomFilter = RawBloomFilterSerializer.read(bytes)

// = UInt32("fba4c795")
}
