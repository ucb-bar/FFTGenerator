//******************************************************************************
// Copyright (c) 2021-2022, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
package fftgenerator
import chisel3._
import chisel3.experimental.{FixedPoint}
import chisel3.util.{log2Ceil}
import dsptools.numbers.{DspComplex, Real}
import scala.util.Random
import scala.math._

import scala.collection.mutable.Map

/**
  * Case class for holding FFT configuration information
  * Also calculates lots of useful intermediate values for FFTs in general, such as
  * pipeline register locations and twiddle factors
  * @param n Total size of the FFT
  * @param pipelineDepth Number of pipeline registers inserted (locations automatically chosen)
  * @param lanes Number of parallel input and output lanes
  */
trait FFTConfig[T <: Data] {
  val genIn: DspComplex[T]
  val genOut: DspComplex[T]
  val n: Int
  val pipelineDepth: Int
  val lanes: Int

  // bit reverse a value
  def bit_reverse(in: Int, width: Int): Int = {
    var test = in
    var out = 0
    for (i <- 0 until width) {
      if (test / pow(2, width-i-1) >= 1) {
        out += pow(2,i).toInt
        test -= pow(2,width-i-1).toInt
      }
    }
    out
  }

  // bp stands for biplex points, so the biplex FFT is a bp-point FFT
  val bp = n/lanes

  // pipelining
  val num = (log2Ceil(n)+1).toDouble
  val ratio = num/(pipelineDepth%log2Ceil(n)+1)
  val stages_to_pipeline = (0 until pipelineDepth%log2Ceil(n)).map(x => if (ratio*(x+1) < num/2 && ratio*(x+1)-0.5 == floor(ratio*(x+1))) floor(ratio*(x+1)).toInt else round(ratio*(x+1)).toInt)
  val pipe = (0 until log2Ceil(n)).map(x => floor(pipelineDepth/log2Ceil(n)).toInt + {if (stages_to_pipeline contains (x+1)) 1 else 0})
  val direct_pipe = pipe.drop(log2Ceil(bp)).foldLeft(0)(_+_)
  val biplex_pipe = pipe.dropRight(log2Ceil(lanes)).foldLeft(0)(_+_)
  println("Pipeline registers inserted on stages: " + pipe.toArray.deep.mkString(","))
  println(s"Total biplex pipeline depth: $biplex_pipe")
  println(s"Total direct pipeline depth: $direct_pipe")

  // twiddling
  val twiddle = (0 until n/4).map(x => Array(cos(2*Pi/n*x),-sin(2*Pi/n*x)))

  // indicies to the twiddle factors
  var indices = Array.fill(log2Ceil(n))(0)
  var prev = Array.fill(log2Ceil(n))(0)
  for (i <- 1 until n/2) {
    val next = (0 until log2Ceil(n)).map(x => floor(i/pow(2,x)).toInt).reverse
    prev.zip(next).foreach{case(lanes,n) => {if (n != lanes) indices = indices :+ n}}
    prev = next.toArray
  }
  indices = indices.map(x => bit_reverse(x, log2Ceil(n)-1))

  // take subsets of indices for split FFTs, then bit reverse to permute as needed
  var q = n
  var temp = Array(indices)
  var bindices = Array[Int]()
  while (q > lanes) {
    temp.foreach{x => bindices = bindices ++ x.take(1)}
    temp = temp.map(x => x.drop(1).splitAt((x.size-1)/2)).flatMap(x => Array(x._1, x._2))
    q = q/2
  }
  val dindices = (0 until temp.size).map(x => temp((x*2)%temp.size+x*2/temp.size)).flatten

}

case class FixedFFTConfig(
  IOWidth: Int,
  binaryPoint: Int,
  n: Int,
  pipelineDepth: Int,
  lanes: Int,
) extends FFTConfig[FixedPoint] {
  val genIn = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val genOut = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
}
