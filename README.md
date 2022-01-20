# MMIO-Based FFT Generator for Chipyard

## Configuration
The following configuration creates an 8-point FFT:
```
class FFTRocketConfig extends Config(
  new fftgenerator.WithFFTGenerator(baseAddr=0x2000, numPoints=8) ++
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++
  new chipyard.config.AbstractConfig)
```
where `baseAddress` specifies the starting address of the FFT's read and write lanes. The FFT write lane is always located at `baseAddress`. There is 1 read lane per output point; since this config specifies an 8-point FFT, there will be 8 read lanes. Read lane `i` (which can be loaded from to retrieve output point `i`) will be located at `baseAddr + 64bits (assuming 64bit system) + (i * 8)`.

### Constraints
`baseAddress`: Should be 64-bit aligned
`numPoints`: Should be a multiple of 2


## Usage
Points are passed into the FFT via the single write lane. In C pseudocode, this might look like:
```
for (int i = 0; i < num_points; i++) {
   // FFT_WRITE_LANE = baseAddress
   uint32_t write_val = points[i];
   volatile uint32_t* ptr = (volatile uint32_t*) FFT_WRITE_LANE;
   *ptr = write_val;
}
```

Once the correct number of inputs are passed in (in the config above, 8 values would be passed in), the read lanes can be read from (again in C pseudocode):
```
for (int i = 0; i < num_points; i++) {
    // FFT_RD_LANE_BASE = baseAddress + 64bits (for write lane)
   volatile uint32_t* ptr_0 = (volatile uint32_t*) (FFT_RD_LANE_BASE + (i * 8));
   uint32_t read_val = *ptr_0;
}
```

## Acknowledgements
The code for the FFT Generator was adapted from the ADEPT Lab at UC Berkeley's [Hydra Spine](https://adept.eecs.berkeley.edu/projects/hydra-spine/) project.

Authors for the original project (in no particular order):
- James Dunn, UC Berkeley (dunn@eecs.berkeley.edu) (`Deserialize.scala`, `Tail.scala`, `Unscramble.scala`)
- Stevo Bailey (stevo.bailey@berkeley.edu) (`FFT.scala`)
