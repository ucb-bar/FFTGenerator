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

// parameter to set FFT mmio registers base address
case object FFTBaseAddrKey extends Field[Int](0x2000)

class WithFFTBaseAddr (baseAddress: Int) extends Config((site, here, up) => {
  case FFTBaseAddrKey => baseAddress
})

case object FFTNumPoints extends Field[Int](2)

class WithFFTNumPoints (numPoints: Int) extends Config((site, here, up) => {
  case FFTNumPoints => numPoints
})