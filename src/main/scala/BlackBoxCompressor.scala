
// Author:  Davide Conficconi
// Date: 14/08/2018
// Revision: 0

package bismo

import Chisel._
import fpgatidbits.synthutils.PrintableParam

// wraps the FPGA-optimized VHDL compressor generator originally developed by
// Thomas Preusser. although the generator supports multi-bit operands, we only
// use it for regular binary operands here.

class BlackBoxCompressorParams(
  val N: Int, // bitwidth of compressor inputs
  val D: Int, // number of pipeline registers, subject to
  val WD: Int = 1, // input operand 1 precision
  val WC: Int = 1 // input operand 2 precision
// compressor tree depth. set to -1 for maximum.
) extends PrintableParam {
  def headersAsList(): List[String] = {
    return List("Dk", "WA", "WB", "BBCompressorLatency")
  }

  def contentAsList(): List[String] = {
    return List(N, WD, WC, getLatency()).map(_.toString)
  }

  // current compressor tree depth generated for a few values of N.
  val depthMap = Map(32 -> 2, 64 -> 3, 128 -> 3, 256 -> 4, 512 -> 5)

  def getLatency(): Int = {
    if (D == -1) {
      if (depthMap.contains(N)) {
        return depthMap(N)
      } else {
        println(s"WARNING BlackBoxCompressor: Depth for N=$N not precomputed, defaulting to 0")
        return 0
      }
    } else {
      return D
    }
  }
}

// Chisel Module wrapper around generated compressor
class BlackBoxCompressor(p: BlackBoxCompressorParams) extends Module {
  def outputbits = log2Up(p.N) + 1
  val io = new Bundle {
    val c = Bits(INPUT, width = p.N)
    val d = Bits(INPUT, width = p.N)
    val r = Bits(OUTPUT, width = outputbits)
  }
  val inst = Module(new mac(
    BB_WA = outputbits, BB_N = p.N, BB_WD = p.WD, BB_WC = p.WC,
    BB_D = p.getLatency())).io
  inst.a := UInt(0)
  inst <> io
}

// actual BlackBox that instantiates the VHDL unit
class mac(
  BB_WA: Int, // result precision
  BB_N: Int, // number of elements in dot product
  BB_WD: Int, // input operand 1 precision
  BB_WC: Int, // input operand 2 precision
  BB_D: Int // optional pipeline regs to add
) extends BlackBox {
  val io = new Bundle {
    // accumulator input is unused
    val a = Bits(INPUT, width = BB_WA)
    // c and d are the inputs to the binary dot product
    val c = Bits(INPUT, width = BB_N * BB_WC)
    val d = Bits(INPUT, width = BB_N * BB_WD)
    // r contains the result after D cycles
    val r = Bits(OUTPUT, width = BB_WA)
    a.setName("a")
    c.setName("c")
    d.setName("d")
    r.setName("r")
  }
  setVerilogParameters(new VerilogParameters {
    val WA: Int = BB_WA
    val N: Int = BB_N
    val WD: Int = BB_WD
    val WC: Int = BB_WC
    val DEPTH: Int = BB_D
  })

  // clock needs to be added manually to BlackBox
  addClock(Driver.implicitClock)

  // Behavioral model for compressor: delayed AND-popcount
  if (BB_WD == 1 && BB_WC == 1) {
    io.r := ShiftRegister(PopCount(io.c & io.d), BB_D)
  }
}

// Chisel Module wrapper around generated compressor
class CharacterizationBBCompressor(p: BlackBoxCompressorParams) extends Module {
  //def outputbits = log2Up(p.N) + 1
  val io = new Bundle {
    val c = Bits(INPUT, width = p.N)
    val d = Bits(INPUT, width = p.N)
    val r = Bits(OUTPUT, width = 32)
    val clean = Bool(INPUT)
  }
  val inst = Module(new BlackBoxCompressor(p)).io

  val cReg = Reg(next = io.c)
  inst.c := cReg
  val dReg = Reg(next = io.d)
  inst.d := dReg

  val rReg = Reg(init = UInt(0, 32))
  when(io.clean) {
    rReg := UInt(0, 32)
  }.otherwise {
    rReg := rReg + inst.r
  }
  io.r := rReg
}
