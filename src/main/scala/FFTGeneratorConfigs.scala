//******************************************************************************
// Copyright (c) 2021-2022, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
package fftgenerator

import org.chipsalliance.cde.config.{Field, Parameters, Config}
import org.chipsalliance.diplomacy.lazymodule.{LazyModule}
import freechips.rocketchip.subsystem.{PBUS}
import testchipip.soc.{SubsystemInjector, SubsystemInjectorKey}
import freechips.rocketchip.tilelink.{TLFragmenter}

//parameter to enable FFT
case object FFTEnableKey extends Field[Option[FixedTailParams]](None)

case object FFTDeviceInjector extends SubsystemInjector((p, baseSubsystem) => {
  if (!p(FFTEnableKey).isEmpty) {
    implicit val q: Parameters = p // pass p implicitly
    // instantiate tail chain
    val pbus = baseSubsystem.locateTLBusWrapper(PBUS)
    val domain = pbus.generateSynchronousDomain.suggestName("fft_domain")
    val tailChain = domain { LazyModule(new LazyTail(p(FFTEnableKey).get)) }
    // connect memory interfaces to pbus
    pbus.coupleTo("tailWrite") { domain { tailChain.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) } := _ }
  }
})


class WithFFTGenerator (baseAddr: Int = 0x2400, numPoints: Int, width: Int = 16, decPt: Int = 8) extends Config((site, here, up) => {
  case FFTEnableKey => Some(FixedTailParams(baseAddress = baseAddr,
    n = numPoints, lanes = numPoints, IOWidth = width, binaryPoint = decPt))
  case SubsystemInjectorKey => up(SubsystemInjectorKey) + FFTDeviceInjector
})
