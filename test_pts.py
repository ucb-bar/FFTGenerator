#!/usr/bin/env python3
"""
Generates test points for FFT
pip dependencies:
numpy==1.13.3
numfi==0.2.4
"""

import cmath
from cmath import e, pi
from numpy import fft

SHOW_DEBUG_INFO = False # prints out step-by-step generation of test points

IOWidth = 16        # IOWidth from Tail.scala
bp = 8              # BP from Tail.scala

freq = 16           # 16 mhz
freq_samp = 128     # 128 mhz
points_n = range(1, 9)

# e^(-2pi * f/fs * nj)
points = []
for n in points_n:
    exponent = (-2 * pi * (freq / freq_samp) * complex(0, n) )
    points.append(e ** exponent) # complex(0, n) = nj

# This prints approximately what you should expect to see from the hardware FFT output
new_points = [complex(int(point.real * 2**8) / 2**8, int(point.imag * 2**8) / 2**8) for point in points]
print(f"FFT Expected Output:\n{fft.fft(new_points)}\nActual output may differ since 1 bit position may vary between hw and reference\n")

from numfi import *

points_in_bin = ""
for n in points_n:
    point = points[n - 1]
    numfi_real = numfi(point.real, 1, IOWidth, bp) # arg0: input float, arg1: signed(1)/unsigned(0), arg2: total width, arg3: fracwidth
    numfi_imag = numfi(point.imag, 1, IOWidth, bp)
    real_bits = f"{numfi_real.bin}"[2:-2]
    imag_bits = f"{numfi_imag.bin}"[2:-2]
    points_in_bin += f"0b{(real_bits + imag_bits)},\n"
    if SHOW_DEBUG_INFO:
        print(f"=== POINT {n} === ")
        print(f"Real:\n\tFloat:{point.real}\n\tFP:{numfi_real}\n\tBIN:{numfi_real.bin}")
        print(f"Imag:\n\tFloat:{point.imag}\n\tFP:{numfi_imag}\n\tBIN:{numfi_imag.bin}")
        print(f"fft-test: \n\tbinary input: {real_bits + imag_bits}")
        print()

print("Test points:")
print(points_in_bin.strip()[:-1])
