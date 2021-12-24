#define FFT_WRITE_LANE  0x2000
#define FFT_RD_LANE_BASE 0x2008
// addr of read lane i is FFT_RD_LANE_BASE + i * 8

#include <stdio.h>
#include <inttypes.h>

static inline uint64_t reg_read64(uintptr_t addr, uint64_t* data)
{
	volatile uint64_t *ptr = (volatile uint64_t *) addr;
    *data = *ptr;
	return *data;
}

static inline void reg_write64(uintptr_t addr, uint64_t data)
{
	volatile uint64_t *ptr = (volatile uint64_t *) addr;
	*ptr = data;
}

int main(void) {
  int num_points = 8;

  // from test_pts.py
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

  for (int i = 0; i < num_points; i++) {
    volatile uint32_t* ptr_0 = (volatile uint32_t*) (FFT_RD_LANE_BASE + (i * 8));
    uint32_t read_val = *ptr_0;
    uint16_t real_part = read_val >> 16;
    uint16_t imaginary_part = read_val & 0xFFFF;
    printf("Read %d:\n\tR:%" PRIu16 "\n\tI:%" PRIu16 "\n", i, real_part, imaginary_part);
  }

  return 0;
}