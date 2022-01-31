//******************************************************************************
// Copyright (c) 2021-2022, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------

package fftgenerator

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util.Decoupled
import chisel3.util._
import dspjunctions._
import dsptools.numbers._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem.BaseSubsystem

// comment when using FixedPoint, uncomment for DspReal
// import dsptools.numbers.implicits._

import dsptools.numbers.{DspComplex, Real}
import scala.util.Random
import scala.math.{pow, abs, round}

import dspblocks._


import dsptools._

/**
  * This block performs an unscrambling of the direct-form FFT outputs. For
  * cases when number of lanes = number of FFT points, this is just a bit-
  * reversal. This logic was taken from Stevo's FFT generator tester,
  * FFTSpec.scala.
  */

/**
  * Base class for Unscramble parameters.
  * These are type-generic.
  */

trait UnscrambleParams[T <: Data] {
  // Datatype of input and output samples
  val protoIn, protoOut: DspComplex[T]
  // Deserialization factor
  val lanes: Int
}

/**
  * Unscramble parameters for fixed-point implementation.
  */
case class FixedUnscrambleParams(
  IOWidth: Int,
  binaryPoint: Int,
  lanes: Int,
) extends UnscrambleParams[FixedPoint] {
    val protoIn, protoOut= DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
}

/**
  * Bundle type as IO for Unscramble modules
  */
class UnscrambleIO[T <: Data](params: UnscrambleParams[T]) extends Bundle {
  // Input samples (single input, time-series)
  val in = Input(ValidWithSync(Vec(params.lanes, params.protoOut.cloneType)))
  // Unscrambled output (parallel vector)
  // Change this to Decoupled later
  val out = Decoupled((Vec(params.lanes, params.protoOut.cloneType)))
}
object UnscrambleIO {
  def apply[T <: Data](params: UnscrambleParams[T]): UnscrambleIO[T] =
    new UnscrambleIO(params)
}

/**
  * Here is the Unscrambler itself.
  */
class Unscramble[T <: Data](val params: UnscrambleParams[T]) extends Module {

  val io = IO(UnscrambleIO(params))

  def bitReverse(in: Int, width: Int): Int = {
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

  for (lane <- 0 until params.lanes) {
    val flippedLane = bitReverse(lane, log2Up(params.lanes))
    io.out.bits(lane) := io.in.bits(flippedLane)
  }
  io.out.valid := io.in.valid
}
