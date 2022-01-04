// Author: James Dunn, UC Berkeley (dunn@eecs.berkeley.edu)

/* Animesh's Notes
Tail.scala is the top of the system

Input for FFT: `val signalIn` in class TailIO
  signalIn is a vector of K complex numbers (specified by TailParams)

Connection:

signalIn -> Deserialize -> FFT -> Unscramble -> FFTFSM -> MatInverse -> OUTPUT
                                   |
                                    -> MatVecMul -> OUTPUT

Notes from Yue:
  Probably don't need deserialize at all
    just need to create a buffer of size n=# of points for FFT
      Deserialize does this but with MIMO prefix/suffix that we don't need here

  Don't need anything after unscramble. Delete it!

  This uses chisel3.dsptools and that will be deprecated soon
    don't worry about this for now!


Notes from 10/29:
  1. Convert Tail to a lazy module
    - create a new module LazyTail
        - `extends LazyModule` instead of `extends Module` to make it a lazy module (with a diplomatic section)
    - Regmap should live inside this new lazy module
        - should wire it to the input and output of the Tail module

    - put everything existing in the LazyModuleImp (non-diplomatic section)
    - diplomatic section is going to be the TLRegisterNode (this specifies the base memory address and connects it to main mem -- example: https://github.com/ucberkeley-ee290c/fa18-gps/blob/50189c74f92dddaf88ba9a8ac322d4e21f140746/src/main/scala/gps/FFT/WriteQueueBlock.scala#L110)
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

import breeze.math.Complex
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

/* This is already defined in the Spine
trait FileNameParams{
  val FileNames: Seq[String]
}*/

// todo: FFT uses DspComplex which is from dsptools and that's getting deprecated. Can we move DspComplex/fixedPoint/etc
// directly into FFT from there?
// todo: make a list of everything that uses dsptools. See if that can be moved directly into FFT repo
// ANSWER: nontrivial amount of dependencies so can't just copy-paste
    // uses complex numbers, fixedpoint, dsptools computation tools for those (adding stages to multiplication etc)

case class FixedTailParams(
    IOWidth: Int = 16,
    binaryPoint: Int = 8,
    lanes: Int = 2, // todo ask yue: why are N and lanes different? -- can remove N, only lanes is used by provided code
    n: Int = 2,
    S: Int = 256,
    pipelineDepth: Int = 0,
) extends TailParams[FixedPoint] {
    // *** Change proto to protoIn & protoOut in FFTFSM?
    // todo ask yue: can we make protoIn and protoOutDes the same? Why are they different? -- can change in and out to be the same
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

    // Inputs:
    // -- From Rocket
    // -- From Spine
    val signalIn = Flipped(Decoupled(params.protoIn.cloneType)) // (decoupled: adds ready-valid (todo? for vector as a whole or for each elem of vector) (vector of k complex numbers))
    // Animesh: params.K = number of FFTs that we have (1 fft is enough so k = 1)
    //          protoIn is 16bit complex number 8r, 8i bits

    // Outputs
    // -- Signal Output
    val signalOut = Decoupled((Vec(params.lanes, params.protoOut.cloneType)))

    // val weightsOut = Vec(params.K, Vec(params.K, Decoupled(Vec(params.S, params.proto.cloneType))))
    // val signalOut = Decoupled(Vec(params.S, Vec(params.K, params.protoOut.cloneType)))
    override def cloneType: this.type = TailIO(params).asInstanceOf[this.type]
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

  /* Animesh:
    assuming K = 1:
      1 signalIn, this is a stream of 16bit complex num (8r8i)
      Deserialize waits for (n=128 or other amount) complex numbers to arrive, aggregates them into one vector, passes that to FFT
  */
    // Connect top-level signalIn to Deserialize
    // Animesh: Deserialize takes in
    DeserializeModule.in.bits := io.signalIn.bits
    DeserializeModule.in.valid := io.signalIn.valid // Animesh: signalIn.valid = new point being passed in
                                                       // better to write our own Deserialize -- say n = 8, k = 1
                                                       // create 8 write registers, 8 read registers
                                                       // pass in a sinusoid input (generate in matlab, pass in 8 samples)
                                                        // output would be: 1 read register set, rest would be 0
                                                        // sinusoid frequency should be a multiple of n = 8, samples should cover at least 2 periods
    io.signalIn.ready := DeserializeModule.in.ready

    // Connect Deserialize to FFT
    for (j <- 0 until params.lanes) FFTModule.in.bits(j) := DeserializeModule.out.bits(j)
    FFTModule.in.valid := DeserializeModule.out.valid
    FFTModule.in.sync := DeserializeModule.out.sync // todo ask yue: what does sync do? -- not used but leave it in assigned to false in Deserialize

    // Connect FFT to Unscramble
    /* Animesh: FFT outputs values with bits reversed (Ex: input of 001 becomes output of 100)
       Unscramble fixes the bit order
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
    address = Seq(AddressSet(p(FFTBaseAddrKey), 0xff)), // (base address + size) of regmap
    device = device,
    beatBytes = 8, // specifies interface width in bytes -- since we're connected to a 64bit bus, want an 8byte width (default is 4)
    concurrency = 1 // size of the internal queue for TileLink requests, must be >0 for decoupled requests and responses (9.4 chipyard docs)
  )

   lazy val module = new LazyModuleImp(this) {
    val tail = Module(new Tail(config))

    val inputWire = Wire(Decoupled(UInt((config.IOWidth * 2).W))) // protoIn defines the input points, are 16 bits
    inputWire.ready := tail.io.signalIn.ready // WriteQueueBlock.scala 67
    tail.io.signalIn.bits := inputWire.bits.asTypeOf(config.protoIn) // WriteQueueBlock.scala 66
    tail.io.signalIn.valid := inputWire.valid

    var outputRegs = new ListBuffer[chisel3.UInt]() // todo ask abe: Is this the right type annotation?? Is it UInt or Regsmth
    for (i <- 0 until config.n) { // todo parameterize
      outputRegs += RegEnable(tail.io.signalOut.bits(i).asUInt(), 0.U, tail.io.signalOut.valid)
    }

    var regMap = new ListBuffer[(Int, Seq[freechips.rocketchip.regmapper.RegField])]()
    regMap += (0x00 -> Seq(RegField.w(config.IOWidth * 2, inputWire)))

    // var regmapSeq: Seq[(Int, Seq[RegField])] = Seq( (0x00, Seq(RegField.w(16, inputWire))) )
    for (i <- 0 until config.n) { // todo parameterize
      regMap += (0x00 + (i+1) * 8 -> Seq(RegField.r(config.IOWidth * 2, outputRegs(i))))
    }

    // val outputReg0 = RegEnable(tail.io.signalOut.bits(0).asUInt(), 0.U, tail.io.signalOut.valid)
    // val outputReg1 = RegEnable(tail.io.signalOut.bits(1).asUInt(), 0.U, tail.io.signalOut.valid)
    // val outputReg2 = RegEnable(tail.io.signalOut.bits(2).asUInt(), 0.U, tail.io.signalOut.valid)
    // val outputReg3 = RegEnable(tail.io.signalOut.bits(3).asUInt(), 0.U, tail.io.signalOut.valid)
    tail.io.signalOut.ready := true.B // todo ask abe: gotta be a better way to drive this

    node.regmap(
      (regMap.toList):_*
    )
  }

}

// todo: make regmpa outputRegX parameterizable

// animesh: the trait is called a mixin
trait CanHavePeripheryFFT extends BaseSubsystem { // animesh: you added this
  if (!p(FFTEnableKey).isEmpty) { // animesh: p is of type parameters (set by BaseSubsytem) todo: find where p is set
    // instantiate tail chain
    val config = p(FFTEnableKey).get.copy(n = p(FFTNumPoints), lanes = p(FFTNumPoints)) // todo ask abe: is there a better way of doing this (since we're optioning the enable) -- could bundle it directly into enable
    val tailChain = LazyModule(new LazyTail(config))
    // connect memory interfaces to pbus
    pbus.toVariableWidthSlave(Some("tailWrite")) { tailChain.node }
  }
}
