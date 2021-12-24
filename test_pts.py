# Generates test points for FFT
import cmath
from cmath import e, pi

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
    point = points[-1]

# pip install numfi
from numfi import *

for n in points_n:
    point = points[n - 1]
    numfi_real = numfi(point.real, 1, IOWidth, bp) # arg0: input float, arg1: signed(1)/unsigned(0), arg2: total width, arg3: fracwidth
    numfi_imag = numfi(point.imag, 1, IOWidth, bp)
    print(f"=== POINT {n} === ")
    # print(f"Real:\n\tFloat:{point.real}\n\tFP:{numfi_real}\n\tBIN:{numfi_real.bin}")
    # print(f"Imag:\n\tFloat:{point.imag}\n\tFP:{numfi_imag}\n\tBIN:{numfi_imag.bin}")
    real_bits = f"{numfi_real.bin}"[2:-2]
    imag_bits = f"{numfi_imag.bin}"[2:-2]
    print(f"fft-test: \n\tbinary input: {real_bits + imag_bits}")
    print()

