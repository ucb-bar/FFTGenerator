//******************************************************************************
// Copyright (c) 2021-2022, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
package fftgenerator

import freechips.rocketchip.config.{Field, Parameters, Config}

//parameter to enable FFT
case object FFTEnableKey extends Field[Option[FixedTailParams]](None)

// baseAddr: Base address of FFT generator (location of FFT write lane).
//    Read lane i will be located at baseAddr + 64bits (assuming 64bit system) + (i * 8)
//    0x2000 picked as default since (at time of creation) no other chipyard components conflict with it
// numPoints: number of points the FFT will take in.
class WithFFTGenerator (baseAddr: Int = 0x2000, numPoints: Int) extends Config((site, here, up) => {
  case FFTEnableKey => Some(FixedTailParams(baseAddress = baseAddr, n = numPoints, lanes = numPoints))
})