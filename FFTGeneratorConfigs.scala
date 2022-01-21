//******************************************************************************
// Copyright (c) 2021-2022, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
package fftgenerator

import freechips.rocketchip.config.{Field, Parameters, Config}

//parameter to enable FFT
case object FFTEnableKey extends Field[Option[FixedTailParams]](None)

class WithFFTGenerator (baseAddr: Int = 0x2000, numPoints: Int, width: Int = 16, decPt: Int = 8) extends Config((site, here, up) => {
  case FFTEnableKey => Some(FixedTailParams(baseAddress = baseAddr, n = numPoints, lanes = numPoints, IOWidth = width, binaryPoint = decPt))
})