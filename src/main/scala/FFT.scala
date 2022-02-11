//******************************************************************************
// Copyright (c) 2021-2022, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------

package fftgenerator

import chisel3.util._
import chisel3._
import chisel3.experimental._
import chisel3.ExplicitCompileOptions
import chisel3.internal.firrtl.KnownBinaryPoint
import craft._
import dsptools._
import dsptools.numbers._
import dsptools.numbers.implicits._
import dspjunctions._
import dspblocks._
import scala.math._


class DirectFFTIO[T<:Data:Real](genMid: DspComplex[T], genOut: DspComplex[T], lanes: Int) extends Bundle {
  val in = Input(ValidWithSync(Vec(lanes, genMid)))
  val out = Output(ValidWithSync(Vec(lanes, genOut)))
}

/**
 * fast fourier transform - cooley-tukey algorithm, decimation-in-time
 * direct form version
 * note, this is always a p-point FFT, though the twiddle factors will be different if p < n
 * @tparam T
 */
class DirectFFT[T<:Data:Real](params: FFTConfig[T], genMid: DspComplex[T], genTwiddle: DspComplex[T], genOutFull: DspComplex[T]) extends Module {
  val io = IO(new DirectFFTIO[T](genMid, genOutFull, params.lanes))

  // synchronize
  val valid_delay = RegNext(io.in.valid)
  val sync = CounterWithReset(true.B, params.bp, io.in.sync, ~valid_delay & io.in.valid)._1
  io.out.sync := ShiftRegisterWithReset(io.in.valid && sync === (params.bp-1).U, params.direct_pipe, 0.U) // should valid keep sync from propagating?
  io.out.valid := ShiftRegisterWithReset(io.in.valid, params.direct_pipe, 0.U)

  // wire up twiddles
  val genTwiddleReal = genTwiddle.real
  val genTwiddleImag = genTwiddle.imag

  val twiddle_rom = VecInit(params.twiddle.map( x => {
    val real = Wire(genTwiddleReal.cloneType)
    val imag = Wire(genTwiddleImag.cloneType)
    real := Real[T].fromDouble(x(0), genTwiddleReal)
    imag := Real[T].fromDouble(x(1), genTwiddleImag)
    val twiddle = Wire(DspComplex(genTwiddleReal, genTwiddleImag))
    twiddle.real := real
    twiddle.imag := imag
    twiddle
  }))
  val indices_rom = VecInit(params.dindices.map(x => x.U))

  val start = sync*(params.lanes-1).U
  val twiddle = Wire(Vec(params.lanes-1, genTwiddle.cloneType))
  // special case when n = 4, because the pattern breaks down
  if (params.n == 4) {
    twiddle := VecInit((0 until params.lanes-1).map(x => {
      val true_branch  = Wire(genTwiddle)
      true_branch     := twiddle_rom(0).divj()
      val false_branch = Wire(genTwiddle)
      false_branch    := twiddle_rom(0)
      Mux(
        indices_rom(start+x.U)(log2Ceil(params.n/4)),
        true_branch,
        false_branch
      )
    }))
  } else {
    twiddle.zipWithIndex.foreach { case (t, x) =>
      t := {
       val true_branch = twiddle_rom(indices_rom(start+x.U)(log2Ceil(params.n/4)-1, 0)).divj().asTypeOf(genTwiddle)
       val false_branch = twiddle_rom(indices_rom(start+x.U)).asTypeOf(genTwiddle)
       val index = indices_rom(start+x.U)
       Mux(index(log2Ceil(params.n/4)),
         true_branch,
         false_branch
       )
      }
    }
  }

  // p-point decimation-in-time direct form FFT with inputs in normal order
  // (outputs bit reversed)
  val stage_outputs = List.fill(log2Ceil(params.lanes)+1)(List.fill(params.lanes)(Wire(genOutFull)))
  io.in.bits.zip(stage_outputs(0)).foreach { case(in, out) => out := in }

  // indices to the twiddle Vec
  var indices = List(List(0,1),List(0,2))
  for (i <- 0 until log2Ceil(params.lanes)-2) {
    indices = indices.map(x => x.map(y => y+1))
    val indices_max = indices.foldLeft(0)((b,a) => max(b,a.reduceLeft((d,c) => max(c,d))))
    indices = indices ++ indices.map(x => x.map(y => y+indices_max))
    indices = indices.map(x => 0 +: x)
  }

  // create the FFT hardware
  for (i <- 0 until log2Ceil(params.lanes)) {
    for (j <- 0 until params.lanes/2) {

      val skip = pow(2,log2Ceil(params.n/2)-(i+log2Ceil(params.bp))).toInt
      val start = ((j % skip) + floor(j/skip) * skip*2).toInt

      // hook it up
      val outputs           = List(stage_outputs(i+1)(start), stage_outputs(i+1)(start+skip))
      val shr_delay         = params.pipe.drop(log2Ceil(params.bp)).dropRight(log2Ceil(params.lanes)-i).foldLeft(0)(_+_)
      val shr               = ShiftRegisterMem[DspComplex[T]](twiddle(indices(j)(i)), shr_delay, name = this.name + s"_${i}_${j}_twiddle_sram")
      val butterfly_outputs = Butterfly[T](Seq(stage_outputs(i)(start), stage_outputs(i)(start+skip)), shr)
      outputs.zip(butterfly_outputs).foreach { x =>
        x._1 := ShiftRegisterMem(x._2, params.pipe(i+log2Ceil(params.bp)), name = this.name + s"_${i}_${j}_pipeline_sram")
      }

    }
  }

  // wire up top-level outputs
  // note, truncation happens here!
  io.out.bits := stage_outputs(log2Ceil(params.lanes))
}

class BiplexFFTIO[T<:Data:Real](lanes: Int, genIn: DspComplex[T], genMid: DspComplex[T]) extends Bundle {
  val in = Input(ValidWithSync(Vec(lanes, genIn)))
  val out = Output(ValidWithSync(Vec(lanes, genMid)))
}

/**
 * fast fourier transform - cooley-tukey algorithm, decimation-in-time
 * biplex pipelined version
 * note, this is always a bp-point FFT
 * @tparam T
 */
class BiplexFFT[T<:Data:Real](params: FFTConfig[T], genMid: DspComplex[T], genTwiddle: DspComplex[T]) extends Module {
  val io = IO(new BiplexFFTIO[T](params.lanes, params.genIn, genMid))

  // synchronize
  val stage_delays = (0 until log2Ceil(params.bp)+1).map(x => { if (x == log2Ceil(params.bp)) params.bp/2 else (params.bp/pow(2,x+1)).toInt })
  val sync = List.fill(log2Ceil(params.bp)+1)(Wire(UInt(width=log2Ceil(params.bp).W)))
  val valid_delay = RegNext(io.in.valid)
  sync(0) := CounterWithReset(true.B, params.bp, io.in.sync, ~valid_delay & io.in.valid)._1
  sync.drop(1).zip(sync).zip(stage_delays).foreach { case ((next, prev), delay) => next := ShiftRegisterWithReset(prev, delay, 0.U) }
  io.out.sync := sync(log2Ceil(params.bp)) === ((params.bp/2-1+params.biplex_pipe)%params.bp).U
  io.out.valid := ShiftRegisterWithReset(io.in.valid, stage_delays.reduce(_+_) + params.biplex_pipe, 0.U)

  // wire up twiddles
  val genTwiddleReal = genTwiddle.real
  val genTwiddleImag = genTwiddle.imag
  val twiddle_rom = VecInit(params.twiddle.map(x => {
    val real = Wire(genTwiddleReal.cloneType)
    val imag = Wire(genTwiddleImag.cloneType)
    real := Real[T].fromDouble(x(0), genTwiddleReal)
    imag := Real[T].fromDouble(x(1), genTwiddleImag)
    val twiddle = Wire(DspComplex(genTwiddleReal, genTwiddleImag))
    twiddle.real := real
    twiddle.imag := imag
    twiddle
  }))
  val indices_rom = VecInit(params.bindices.map(x => x.U))
  val indices = (0 until log2Ceil(params.bp)).map(x => indices_rom((pow(2,x)-1).toInt.U +& { if (x == 0) 0.U else ShiftRegisterMem(sync(x+1), params.pipe.dropRight(log2Ceil(params.n)-x).reduceRight(_+_), name = this.name + s"_twiddle_sram")(log2Ceil(params.bp)-2,log2Ceil(params.bp)-1-x) }))
  val twiddle = Wire(Vec(log2Ceil(params.bp), genTwiddle))
  // special cases
  if (params.n == 4) {
    twiddle := VecInit((0 until log2Ceil(params.bp)).map(x => {
      val true_branch  = Wire(genTwiddle)
      val false_branch = Wire(genTwiddle)
      true_branch     := twiddle_rom(0).divj()
      false_branch    := twiddle_rom(0)
      Mux(indices(x)(log2Ceil(params.n/4)), true_branch, false_branch)
    }))
  } else if (params.bp == 2) {
    twiddle := VecInit((0 until log2Ceil(params.bp)).map(x =>
        twiddle_rom(indices(x))
        ))
  } else {
    twiddle := VecInit((0 until log2Ceil(params.bp)).map(x => {
      val true_branch  = Wire(genTwiddle)
      val false_branch = Wire(genTwiddle)
      true_branch     := twiddle_rom(indices(x)(log2Ceil(params.n/4)-1, 0)).divj()
      false_branch    := twiddle_rom(indices(x))
      Mux(indices(x)(log2Ceil(params.n/4)), true_branch, false_branch)
    }))
  }

  // bp-point decimation-in-time biplex pipelined FFT with outputs in bit-reversed order
  // up-scale to genMid immediately for simplicity
  val stage_outputs = List.fill(log2Ceil(params.bp)+2)(List.fill(params.lanes)(Wire(genMid)))
  io.in.bits.zip(stage_outputs(0)).foreach { case(in, out) => out := in }

  // create the FFT hardware
  for (i <- 0 until log2Ceil(params.bp)+1) {
    for (j <- 0 until params.lanes/2) {

      val skip = 1
      val start = j*2

      // hook it up
      // last stage just has one extra permutation, no butterfly
      val mux_out = BarrelShifter(VecInit(stage_outputs(i)(start), ShiftRegisterMem(stage_outputs(i)(start+skip), stage_delays(i), name = this.name + s"_${i}_${j}_mux1_sram")), ShiftRegisterMem(sync(i)(log2Ceil(params.bp)-1 - { if (i == log2Ceil(params.bp)) 0 else i }), {if (i == 0) 0 else params.pipe.dropRight(log2Ceil(params.n)-i).reduceRight(_+_)},  name = this.name + s"_${i}_${j}_mux1_sram"))
      if (i == log2Ceil(params.bp)) {
        Seq(stage_outputs(i+1)(start), stage_outputs(i+1)(start+skip)).zip(Seq(ShiftRegisterMem(mux_out(0), stage_delays(i), name = this.name + s"_${i}_${j}_last_sram" ), mux_out(1))).foreach { x => x._1 := x._2 }
        } else {
          Seq(stage_outputs(i+1)(start), stage_outputs(i+1)(start+skip)).zip(Butterfly(Seq(ShiftRegisterMem(mux_out(0), stage_delays(i), name = this.name + s"_${i}_${j}_pipeline0_sram"), mux_out(1)), twiddle(i))).foreach { x => x._1 := ShiftRegisterMem(x._2, params.pipe(i), name = this.name + s"_${i}_${j}_pipeline1_sram") }
        }

    }
  }

  // wire up top-level outputs
  io.out.bits := stage_outputs(log2Ceil(params.bp)+1)

}

/**
 * IO Bundle for FFT
 * @tparam T
 */
class FFTIO[T<:Data:Real](lanes: Int, genIn: DspComplex[T], genOut: DspComplex[T]) extends Bundle {

  val in = Input(ValidWithSync(Vec(lanes, genIn)))
  val out = Output(ValidWithSync(Vec(lanes, genOut)))
}

/**
 * fast fourier transform - cooley-tukey algorithm, decimation-in-time
 * mixed version
 * note, this is always an n-point FFT
 * @tparam T
 */
class FFT[T<:Data:Real](val params: FFTConfig[T]) extends Module {

  require(params.lanes >= 2, "Must have at least 2 parallel inputs")
  require(isPow2(params.lanes), "FFT parallelism must be a power of 2")
  require(params.lanes <= params.n, "An n-point FFT cannot have more than n inputs (p must be less than or equal to n)")

  val io = IO(new FFTIO(params.lanes, params.genIn, params.genOut))

  // calculate direct FFT input bitwidth
  // this is just the input total width + growth of 1 bit per biplex stage
  val genMid: DspComplex[T] = {
    if (params.bp == 1) { params.genIn }
    else {
      val growth = log2Ceil(params.bp)
      params.genIn.underlyingType() match {
        case "fixed" =>
          params.genIn.real.asInstanceOf[FixedPoint].binaryPoint match {
            case KnownBinaryPoint(binaryPoint) =>
              val totalBits = params.genIn.real.getWidth + growth
              DspComplex(FixedPoint(totalBits.W, binaryPoint.BP), FixedPoint(totalBits.W, binaryPoint.BP)).asInstanceOf[DspComplex[T]]
            case _ => throw new DspException("Error: unknown binary point when calculating FFT bitwdiths")
          }
        case "sint" => {
          val totalBits = params.genIn.real.getWidth + growth
          DspComplex(SInt(totalBits.W), SInt(totalBits.W)).asInstanceOf[DspComplex[T]]
        }
        case _ => throw new DspException("Error: unknown type when calculating FFT bitwidths")
      }
    }
  }

  // calculate twiddle factor bitwidth
  // total input bits
  val genTwiddleBiplex: DspComplex[T] = {
    val growth = log2Ceil(params.bp)
    params.genIn.asInstanceOf[DspComplex[T]].underlyingType() match {
      case "fixed" =>
        params.genIn.asInstanceOf[DspComplex[T]].real.asInstanceOf[FixedPoint].binaryPoint match {
          case KnownBinaryPoint(binaryPoint) =>
            val totalBits = params.genIn.asInstanceOf[DspComplex[T]].real.getWidth + growth
            DspComplex(FixedPoint(totalBits.W, (totalBits-2).BP), FixedPoint(totalBits.W, (totalBits-2).BP)).asInstanceOf[DspComplex[T]]
          case _ => throw new DspException("Error: unknown binary point when calculating FFT bitwdiths")
        }
      case "sint" => {
        val totalBits = params.genIn.asInstanceOf[DspComplex[T]].real.getWidth + growth
        DspComplex(SInt(totalBits.W), SInt(totalBits.W)).asInstanceOf[DspComplex[T]]
      }
      case _ => throw new DspException("Error: unknown type when calculating FFT bitwidths")
    }
  }

  val genTwiddleDirect: DspComplex[T] = {
    val growth = log2Ceil(params.n)
    params.genIn.asInstanceOf[DspComplex[T]].underlyingType() match {
      case "fixed" =>
        params.genIn.asInstanceOf[DspComplex[T]].real.asInstanceOf[FixedPoint].binaryPoint match {
          case KnownBinaryPoint(binaryPoint) =>
            val totalBits = params.genIn.asInstanceOf[DspComplex[T]].real.getWidth + growth
            DspComplex(FixedPoint(totalBits.W, (totalBits-2).BP), FixedPoint(totalBits.W, (totalBits-2).BP)).asInstanceOf[DspComplex[T]]
          case _ => throw new DspException("Error: unknown binary point when calculating FFT bitwdiths")
        }
      case "sint" => {
        val totalBits = params.genIn.asInstanceOf[DspComplex[T]].real.getWidth + growth
        DspComplex(SInt(totalBits.W), SInt(totalBits.W)).asInstanceOf[DspComplex[T]]
      }
      case _ => throw new DspException("Error: unknown type when calculating FFT bitwidths")
    }
  }

  // calculate direct FFT output bitwidth
  // this is just the input total width + growth of 1 bit per FFT stage
  val genOutDirect: DspComplex[T] = {
    if (params.bp == 1) { params.genIn }
    else {
      val growth = log2Ceil(params.n)
      params.genIn.asInstanceOf[DspComplex[T]].underlyingType() match {
        case "fixed" =>
          params.genIn.asInstanceOf[DspComplex[T]].real.asInstanceOf[FixedPoint].binaryPoint match {
            case KnownBinaryPoint(binaryPoint) =>
              val totalBits = params.genIn.asInstanceOf[DspComplex[T]].real.getWidth + growth
              DspComplex(FixedPoint(totalBits.W, binaryPoint.BP), FixedPoint(totalBits.W, binaryPoint.BP)).asInstanceOf[DspComplex[T]]
            case _ => throw new DspException("Error: unknown binary point when calculating FFT bitwdiths")
          }
        case "sint" => {
          val totalBits = params.genIn.asInstanceOf[DspComplex[T]].real.getWidth + growth
          DspComplex(SInt(totalBits.W), SInt(totalBits.W)).asInstanceOf[DspComplex[T]]
        }
        case _ => throw new DspException("Error: unknown type when calculating FFT bitwidths")
      }
    }
  }

  // feed in zeros when invalid
  val in = Wire(ValidWithSync(Vec(params.lanes, params.genIn)))
  when (io.in.valid) {
    in.bits := io.in.bits
  } .otherwise {
    in.bits.foreach { case b =>
      b.real := Real[T].zero
      b.imag := Real[T].zero
    }
  }
  in.valid := io.in.valid
  in.sync := io.in.sync

  // instantiate sub-FFTs
  val direct = Module(new DirectFFT[T](
    params = params,
    genMid = genMid,
    genTwiddle = genTwiddleDirect,
    genOutFull = genOutDirect
  ))
  io.out <> direct.io.out

  if (params.n != params.lanes) {
    val biplex = Module(new BiplexFFT[T](params, genMid, genTwiddleBiplex))
    direct.io.in := biplex.io.out
    biplex.io.in <> in
  } else {
    direct.io.in <> in
  }
}
