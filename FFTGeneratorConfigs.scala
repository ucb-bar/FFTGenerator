//******************************************************************************
// Copyright (c) 2021-2022, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
package fftgenerator

import freechips.rocketchip.config.{Field, Parameters, Config}

//cparameter to enable FFT
case object FFTEnableKey extends Field[Option[FixedTailParams]](None)

class WithFFTGenerator (enable: Boolean = true) extends Config((site, here, up) => {
  case FFTEnableKey => Some(FixedTailParams())
})

class WithFFTBaseAddr (baseAddr: Int) extends Config((site, here, up) => {
  case FFTEnableKey => Some(up(FFTEnableKey, site).get.copy(baseAddress = baseAddr))
})

class WithFFTNumPoints (numPoints: Int) extends Config((site, here, up) => {
  case FFTEnableKey => Some(up(FFTEnableKey, site).get.copy(n = numPoints, lanes = numPoints))
})