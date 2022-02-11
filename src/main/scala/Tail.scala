//******************************************************************************
// Copyright (c) 2021-2022, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------

/**
  * - Tail.scala is the top of the system
  * - Input for FFT: `val signalIn` in class TailIO
  *    - signalIn is a vector of K complex numbers (specified by TailParams)
  * - Connection: signalIn -> Deserialize -> FFT -> Unscramble -> OUTPUT
  */

package fftgenerator

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util._
import chisel3.util.{Decoupled, Counter}
import dsptools.numbers._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem.BaseSubsystem

import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink.TLRegisterNode
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._

import chisel3.experimental._
import chisel3.ExplicitCompileOptions
import chisel3.internal.firrtl.KnownBinaryPoint
import craft._
import dsptools._
import dsptools.numbers.implicits._
import dspjunctions._
import dspblocks._
import scala.math._
import scala.math.sqrt
import scala.collection.mutable.ListBuffer

trait TailParams[T <: Data] extends DeserializeParams[T] with FFTConfig[T] with UnscrambleParams[T] {
  // Number of lanes in FFT. Should be same as N.
  val lanes: Int
  // n-point FFT
  val n: Int
  val S: Int
}

case class FixedTailParams(
  IOWidth: Int = 16,
  binaryPoint: Int = 8,
  lanes: Int = 2,
  n: Int = 2,
  S: Int = 256,
  pipelineDepth: Int = 0, // not configurable since this is an mmio device and not on-pipeline
  baseAddress: Int = 0x2000,
) extends TailParams[FixedPoint] {
  val proto = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val protoIn = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val protoOut = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val genIn = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val genOut = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val protoInDes = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val protoOutDes = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val inA = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val inB = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
  val outC = DspComplex(FixedPoint(IOWidth.W, binaryPoint.BP),FixedPoint(IOWidth.W, binaryPoint.BP))
}

class TailIO[T <: Data](params: TailParams[T]) extends Bundle {

  val signalIn = Flipped(Decoupled(params.protoIn.cloneType)) // (decoupled: adds ready-valid (vector of k complex numbers))

  // Outputs
  // -- Signal Output
  val signalOut = Decoupled((Vec(params.lanes, params.protoOut.cloneType)))
}

object TailIO {
  def apply[T <: Data](params: TailParams[T]): TailIO[T] =
      new TailIO(params)
}

class Tail[T <: Data : RealBits : BinaryRepresentation : Real](val params: TailParams[T]) extends Module {

  val io = IO(TailIO(params))

  // Instantiate the modules
  val DeserializeModule = Module(new Deserialize(params)).io
  val FFTModule = Module(new FFT(params)).io
  val UnscrambleModule = Module(new Unscramble(params)).io

  DeserializeModule.in.bits := io.signalIn.bits
  DeserializeModule.in.valid := io.signalIn.valid // signalIn.valid = new point being passed in
  io.signalIn.ready := DeserializeModule.in.ready

  // Connect Deserialize to FFT
  for (j <- 0 until params.lanes) FFTModule.in.bits(j) := DeserializeModule.out.bits(j)
  FFTModule.in.valid := DeserializeModule.out.valid
  FFTModule.in.sync := DeserializeModule.out.sync

  /* Connect FFT to Unscramble
    * FFT outputs values with bits reversed (Ex: input of 001 becomes output of 100)
    * Unscramble fixes the bit order
    */
  for (j <- 0 until params.lanes) UnscrambleModule.in.bits(j) := FFTModule.out.bits(j)
  UnscrambleModule.in.valid := FFTModule.out.valid
  UnscrambleModule.in.sync := FFTModule.out.sync

  for (j <- 0 until params.lanes) io.signalOut.bits(j) := UnscrambleModule.out.bits(j)
  io.signalOut.valid := UnscrambleModule.out.valid
  UnscrambleModule.out.ready := io.signalOut.ready
}

class LazyTail(val config: FixedTailParams)(implicit p: Parameters) extends LazyModule {
  val device = new SimpleDevice("fft-generator", Seq("cpu")) // add an entry to the DeviceTree in the BootROM so that it can be read by a Linux driver (9.2 chipyard docs)
  val node = TLRegisterNode(
    address = Seq(AddressSet(config.baseAddress, 0xff)), // (base address + size) of regmap
    device = device,
    beatBytes = 8, // specifies interface width in bytes -- since we're connected to a 64bit bus, want an 8byte width (default is 4)
    concurrency = 1 // size of the internal queue for TileLink requests, must be >0 for decoupled requests and responses (9.4 chipyard docs)
  )

  lazy val module = new LazyModuleImp(this) {
    val tail = Module(new Tail(config))

    val inputWire = Wire(Decoupled(UInt((config.IOWidth * 2).W)))
    inputWire.ready := tail.io.signalIn.ready
    tail.io.signalIn.bits := inputWire.bits.asTypeOf(config.protoIn)
    tail.io.signalIn.valid := inputWire.valid

    val outputRegs = tail.io.signalOut.bits.map(b => RegEnable(b.asUInt(), 0.U, tail.io.signalOut.valid))

    var regMap = new ListBuffer[(Int, Seq[freechips.rocketchip.regmapper.RegField])]()
    regMap += (0x00 -> Seq(RegField.w(config.IOWidth * 2, inputWire)))

    for (i <- 0 until config.n) {
      regMap += (0x00 + (i+1) * 8 -> Seq(RegField.r(config.IOWidth * 2, outputRegs(i))))
    }

    tail.io.signalOut.ready := true.B

    node.regmap((regMap.toList):_*)
  }

}

trait CanHavePeripheryFFT extends BaseSubsystem {
  if (!p(FFTEnableKey).isEmpty) {
    // instantiate tail chain
    val tailChain = LazyModule(new LazyTail(p(FFTEnableKey).get))
    // connect memory interfaces to pbus
    pbus.toVariableWidthSlave(Some("tailWrite")) { tailChain.node }
  }
}
