#define FFT_WRITE_LANE  0x2000
#define FFT_RD_LANE_BASE 0x2008
// addr of read lane i is FFT_RD_LANE_BASE + i * 8

#include <stdio.h>
#include <inttypes.h>
#include <math.h>

int main(void) {
  int num_points = 8;

  // from test_pts.py
  // point size (and therefore integer width/uint32_t) determined by IOWidth from Tail.scala
  // point size is 2 * IOWidth since both real and imaginary components get IOWidth bits
  uint32_t points[8] = {
    0b00000000101101011111111101001011, // 00B5FF4B
    0b00000000000000001111111100000000, // 0000FF00
    0b11111111010010111111111101001011, // FF4BFF4B
    0b11111111000000000000000000000000, // FF000000
    0b11111111010010110000000010110101, // FF4B00B5
    0b00000000000000000000000100000000, // 00000100
    0b00000000101101010000000010110101, // 00B500B5
    0b00000001000000000000000000000000  // 01000000
  };

  for (int i = 0; i < num_points; i++) {
    uint32_t write_val = points[i];
    volatile uint32_t* ptr = (volatile uint32_t*) FFT_WRITE_LANE;
    *ptr = write_val;
    // printf("Finished write %d\n", i);
  }

  printf("Test float: %f\n", 1.01f);

  for (int i = 0; i < num_points; i++) {
    volatile uint32_t* ptr_0 = (volatile uint32_t*) (FFT_RD_LANE_BASE + (i * 8));
    uint32_t read_val = *ptr_0;

    uint16_t real_part_bin = read_val >> 16;

    uint16_t imag_part_bin = read_val & 0xFFFF;

    /* To convert binary to floating point
     * However, RISC-V compiler can't print floats. You can use this by copy-pasting it into an online
     * C compiler. The printf at the bottom of this for loop will print out the values of real_part_bin
     * and imag_part_bin
     */
    int bp = 8; // from tail.scala
    float real_comp = ((int16_t) real_part_bin) * pow(2, bp);
    float imag_comp = ((int16_t) imag_part_bin) * pow(2, bp);

    printf("Read %d:\n\tR: %x\n\tI: %x\n", i, real_part_bin, imag_part_bin);
  }

  return 0;
}