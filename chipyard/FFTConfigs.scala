package chipyard

import org.chipsalliance.cde.config.Config

// DOC include start: FFTRocketConfig
class FFTRocketConfig extends Config(
  new fftgenerator.WithFFTGenerator(numPoints = 8, width = 16, decPt = 8) ++ // add 8-point mmio fft at the default addr (0x2400) with 16bit fixed-point numbers.
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)
// DOC include end: FFTRocketConfig

class ManyMMIOAcceleratorRocketConfig extends Config(
  new chipyard.example.WithInitZero(0x88000000L, 0x1000L) ++   // add InitZero
  new fftgenerator.WithFFTGenerator(numPoints = 8, width = 16, decPt = 8) ++ // add 8-point mmio fft at the default addr (0x2400) with 16bit fixed-point numbers.
  new chipyard.example.WithStreamingPassthrough ++          // use top with tilelink-controlled streaming passthrough
  new chipyard.example.WithStreamingFIR ++                  // use top with tilelink-controlled streaming FIR
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

