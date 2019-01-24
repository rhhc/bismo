// Copyright (c) 2018 Norwegian University of Science and Technology (NTNU)
//
// BSD v3 License
//
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// * Redistributions of source code must retain the above copyright notice, this
//   list of conditions and the following disclaimer.
//
// * Redistributions in binary form must reproduce the above copyright notice,
//   this list of conditions and the following disclaimer in the documentation
//   and/or other materials provided with the distribution.
//
// * Neither the name of [project] nor the names of its
//   contributors may be used to endorse or promote products derived from
//   this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package bismo

import Chisel._
import fpgatidbits.dma._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.PlatformWrapper._

// ResultStage is one of the three components of the BISMO pipeline, which
// contains infrastructure to concurrently write result data to DRAM from DPA
// accumulators while the other stages are doing their thing.
// The ResultStage is responsible for writing one or more rows of accumulators
// into DRAM. Internally, once a row is finished, the write addr
// will jump to the next address determined by the runtime matrix size.

class ResultStageParams(
  val accWidth: Int, // accumulator width in bits
  // DPA dimensions
  val dpa_rhs: Int,
  val dpa_lhs: Int,
  val mrp: MemReqParams, // memory request params for platform
  // read latency for result memory
  val resMemReadLatency: Int,
  // whether to transpose accumulator order while writing
  val transpose: Boolean = true,
  // number of entries in the result mem
  val resEntriesPerMem: Int = 2) extends PrintableParam {
  def headersAsList(): List[String] = {
    return List("DRAM_wr", "dpa_rhs", "dpa_lhs", "accWidth")
  }

  def contentAsList(): List[String] = {
    return List(mrp.dataWidth, dpa_rhs, dpa_lhs, accWidth).map(_.toString)
  }

  // total width of all accumulator registers
  def getTotalAccBits(): Int = {
    return accWidth * dpa_rhs * dpa_lhs
  }

  // number of DPA rows
  def getNumRows(): Int = {
    // TODO respect transposition here
    assert(transpose == true)
    return dpa_rhs
  }

  // one row worth of acc. register bits
  def getRowAccBits(): Int = {
    return accWidth * dpa_lhs
  }

  // sanity check parameters
  // channel width must divide row acc bits
  assert(getRowAccBits() % mrp.dataWidth == 0)
  assert(transpose == true)
  // TODO add wait states for resmem read latency to handle this
  assert(resMemReadLatency == 0)
}

class ResultStageCtrlIO(myP: ResultStageParams) extends Bundle {
  // DRAM controls
  val dram_base = UInt(width = 64)
  val dram_skip = UInt(width = 64)
  // wait for completion of all writes (no new DRAM wr generated)
  val waitComplete = Bool()
  val waitCompleteBytes = UInt(width = 32)
  // result memory to read from
  val resmem_addr = UInt(width = log2Up(myP.resEntriesPerMem))

  override def cloneType: this.type =
    new ResultStageCtrlIO(myP).asInstanceOf[this.type]
}

class ResultStageDRAMIO(myP: ResultStageParams) extends Bundle {
  // DRAM write channel
  val wr_req = Decoupled(new GenericMemoryRequest(myP.mrp))
  val wr_dat = Decoupled(UInt(width = myP.mrp.dataWidth))
  val wr_rsp = Decoupled(new GenericMemoryResponse(myP.mrp)).flip

  override def cloneType: this.type =
    new ResultStageDRAMIO(myP).asInstanceOf[this.type]
}

class ResultStage(val myP: ResultStageParams) extends Module {
  val io = new Bundle {
    // base control signals
    val start = Bool(INPUT) // hold high while running
    val done = Bool(OUTPUT) // high when done until start=0
    val csr = new ResultStageCtrlIO(myP).asInput
    val dram = new ResultStageDRAMIO(myP)
    // interface towards result memory
    val resmem_req = Vec.fill(myP.dpa_lhs) {
      Vec.fill(myP.dpa_rhs) {
        new OCMRequest(myP.accWidth, log2Up(myP.resEntriesPerMem)).asOutput
      }
    }
    val resmem_rsp = Vec.fill(myP.dpa_lhs) {
      Vec.fill(myP.dpa_rhs) {
        new OCMResponse(myP.accWidth).asInput
      }
    }
  }
  // TODO add burst support, single beat for now
  val bytesPerBeat: Int = myP.mrp.dataWidth / 8

  // instantiate downsizer
  val ds = Module(new StreamResizer(
    inWidth = myP.getTotalAccBits(), outWidth = myP.mrp.dataWidth)).io
  // instantiate request generator
  val rg = Module(new BlockStridedRqGen(
    mrp = myP.mrp, writeEn = true)).io

  // wire up resmem_req
  for {
    i ← 0 until myP.dpa_lhs
    j ← 0 until myP.dpa_rhs
  } {
    io.resmem_req(i)(j).addr := io.csr.resmem_addr
    io.resmem_req(i)(j).writeEn := Bool(false)
  }

  // wire up downsizer
  val accseq = for {
    j ← 0 until myP.getNumRows()
    i ← 0 until myP.dpa_lhs
  } yield io.resmem_rsp(i)(j).readData
  val allacc = Cat(accseq.reverse)
  ds.in.bits := allacc
  // downsizer input valid controlled by FSM
  ds.in.valid := Bool(false)
  FPGAQueue(ds.out, 256) <> io.dram.wr_dat

  // wire up request generator
  rg.in.valid := Bool(false)
  rg.in.bits.base := io.csr.dram_base
  rg.in.bits.block_step := io.csr.dram_skip
  rg.in.bits.block_count := UInt(myP.getNumRows())
  // TODO fix if we introduce bursts here
  rg.block_intra_step := UInt(bytesPerBeat)
  rg.block_intra_count := UInt(myP.getRowAccBits() / (8 * bytesPerBeat))

  rg.out <> io.dram.wr_req

  // completion detection logic
  val regCompletedWrBytes = Reg(init = UInt(0, 32))
  io.dram.wr_rsp.ready := Bool(true)
  when(io.dram.wr_rsp.valid) {
    regCompletedWrBytes := regCompletedWrBytes + UInt(bytesPerBeat)
  }
  val allComplete = (regCompletedWrBytes === io.csr.waitCompleteBytes)

  // FSM logic for control
  val sIdle :: sWaitDS :: sWaitRG :: sWaitComplete :: sFinished :: Nil = Enum(UInt(), 5)
  val regState = Reg(init = UInt(sIdle))

  io.done := Bool(false)

  switch(regState) {
    is(sIdle) {
      when(io.start) {
        when(io.csr.waitComplete) {
          regState := sWaitComplete
        }.otherwise {
          ds.in.valid := Bool(true)
          rg.in.valid := Bool(true)
          when(ds.in.ready & !rg.in.ready) {
            regState := sWaitRG
          }.elsewhen(!ds.in.ready & rg.in.ready) {
            regState := sWaitDS
          }.elsewhen(ds.in.ready & rg.in.ready) {
            regState := sFinished
          }
        }
      }
    }

    is(sWaitDS) {
      // downsizer is busy but request generator is done
      ds.in.valid := Bool(true)
      when(ds.in.ready) { regState := sFinished }
    }

    is(sWaitRG) {
      // downsizer is done but request generator busy
      rg.in.valid := Bool(true)
      when(rg.in.ready) { regState := sFinished }
    }

    is(sWaitComplete) {
      when(allComplete) {
        regState := sFinished
        regCompletedWrBytes := UInt(0)
      }
    }

    is(sFinished) {
      io.done := Bool(true)
      when(!io.start) { regState := sIdle }
    }
  }
  // debug:
  // uncomment to print issued write requests and data in emulation
  //PrintableBundleStreamMonitor(io.dram.wr_req, Bool(true), "wr_req", true)
  /*when(io.dram.wr_dat.fire()) {
    printf("Write data: %x\n", io.dram.wr_dat.bits)
  }*/
}
