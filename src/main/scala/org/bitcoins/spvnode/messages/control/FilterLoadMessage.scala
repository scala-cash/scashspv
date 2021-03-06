package org.bitcoins.spvnode.messages.control

import org.bitcoins.core.number.{UInt32, UInt64}
import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.util.Factory
import org.bitcoins.spvnode.bloom.{BloomFilter, BloomFlag}
import org.bitcoins.spvnode.messages.FilterLoadMessage
import org.bitcoins.spvnode.serializers.messages.control.RawFilterLoadMessageSerializer

/**
  * Created by chris on 7/19/16.
  * [[https://bitcoin.org/en/developer-reference#filterload]]
  */
object FilterLoadMessage extends Factory[FilterLoadMessage] {
  private case class FilterLoadMessageImpl(bloomFilter: BloomFilter) extends FilterLoadMessage {
    require(bloomFilter.filterSize.num.underlying <= BloomFilter.maxSize.underlying, "Can only have a maximum of 36,000 bytes in our filter, got: " + bloomFilter.data.size)
    require(bloomFilter.hashFuncs <= BloomFilter.maxHashFuncs, "Can only have a maximum of 50 hashFuncs inside FilterLoadMessage, got: " + bloomFilter.hashFuncs)
    require(bloomFilter.filterSize.num.underlying == bloomFilter.data.size, "Filter Size compactSizeUInt and actual filter size were different, " +
      "filterSize: " + bloomFilter.filterSize.num + " actual filter size: " + bloomFilter.data.length)
  }

  override def fromBytes(bytes: Seq[Byte]): FilterLoadMessage = RawFilterLoadMessageSerializer.read(bytes)

  def apply(filterSize: CompactSizeUInt, filter: Seq[Byte], hashFuncs: UInt32, tweak: UInt32, flags: BloomFlag): FilterLoadMessage = {
    val bloomFilter = BloomFilter(filterSize,filter,hashFuncs,tweak,flags)
    FilterLoadMessage(bloomFilter)
  }

  def apply(filter: Seq[Byte], hashFuncs: UInt32, tweak: UInt32, flags: BloomFlag): FilterLoadMessage = {
    val filterSize = CompactSizeUInt(UInt64(filter.length))
    FilterLoadMessage(filterSize,filter,hashFuncs,tweak,flags)
  }

  def apply(bloomFilter: BloomFilter): FilterLoadMessage = {
    FilterLoadMessageImpl(bloomFilter)
  }
}
