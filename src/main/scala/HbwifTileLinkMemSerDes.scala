package hbwif

import Chisel._
import cde._
import junctions._
import uncore.tilelink._
import util.HellaFlowQueue

class HbwifTileLinkMemSerDesIO(implicit val p: Parameters) extends util.ParameterizedBundle()(p)
  with HasHbwifParameters {

  val tx = (new Decoded8b10bSymbol).asOutput
  val rx = (new Decoded8b10bSymbol).asInput

  val mem = (new ClientUncachedTileLinkIO).flip

  val retransmitEnable = Bool(INPUT)
  val retransmitCycles = UInt(INPUT, width = log2Up(hbwifMaxRetransmitCycles))

}

class HbwifTileLinkMemSerDes(implicit val p: Parameters) extends Module
  with HasHbwifTileLinkParameters {

  val io = new HbwifTileLinkMemSerDesIO

  assert(io.mem.acquire.bits.is_builtin_type, "Only builtin types allowed in HBWIF")
  require(hbwifBufferDepth <= (1 << tlClientXactIdBits), "HBWIF buffer depth should be <= (1 << tlClientXactIdBits)")
  require(hbwifBufferDepth == (1 << log2Up(hbwifBufferDepth)), "HBWIF buffer depth should be a power of 2")

  val grantBuffer = Module(new HellaFlowQueue(hbwifBufferDepth * tlDataBeats)(new Grant))
  val grantFilter = Module(new HbwifGrantFilter)
  val grantDeserializer = Module(new HbwifGrantDeserializer)

  val acquireTable = Module(new HbwifAcquireTable)
  val acquireSerializer = Module(new HbwifAcquireSerializer)

  assert(grantBuffer.io.enq.ready, "The grantBuffer must always be ready")
  grantBuffer.io.enq.bits := grantFilter.io.out.bits
  grantBuffer.io.enq.valid := grantFilter.io.out.valid
  io.mem.grant <> grantBuffer.io.deq

  grantFilter.io.in <> grantDeserializer.io.grant
  grantDeserializer.io.serial <> io.rx

  val dec = (io.mem.grant.fire() && io.mem.grant.bits.last())
  val inc = (io.mem.acquire.fire() && io.mem.acquire.bits.last())
  val slots = Reg(init = UInt(0, width = log2Up(hbwifBufferDepth)))
  val full = Reg(init = Bool(false))
  when (dec && ~inc) {
    slots := slots - UInt(1)
    full := Bool(false)
  } .elsewhen (inc && ~dec) {
    slots := slots + UInt(1)
    when (slots === UInt(hbwifBufferDepth - 1)) {
      full := Bool(true)
    }
  }

  io.mem.acquire.ready := !full && acquireTable.io.in.ready
  acquireTable.io.in.valid := !full && io.mem.acquire.valid
  acquireTable.io.in.bits := io.mem.acquire.bits
  acquireTable.io.retransmitEnable := io.retransmitEnable
  acquireTable.io.retransmitCycles := io.retransmitCycles

  acquireTable.io.clear.valid := grantFilter.io.out.valid & grantFilter.io.out.bits.first()
  acquireTable.io.clear.bits := grantFilter.io.out.bits.client_xact_id

  acquireSerializer.io.acquire <> acquireTable.io.out
  io.tx <> acquireSerializer.io.serial

}

class HbwifGrantFilterIO(implicit val p: Parameters) extends util.ParameterizedBundle()(p)
  with HasHbwifParameters {

  val in  = Valid(new Grant).flip
  val out = Valid(new Grant)

}

class HbwifGrantFilter(implicit val p: Parameters) extends Module
  with HasHbwifTileLinkParameters {

  val io = new HbwifGrantFilterIO

  val sWait :: sValid :: sFill :: Nil = Enum(UInt(), 3)
  val state = Reg(init = sWait)

  val buffer = Reg(Vec(tlDataBeats, new Grant()))
  val count = Reg(UInt(width = tlBeatAddrBits))

  io.out.bits  := buffer(UInt(tlDataBeats) - count)
  io.out.valid := (state === sValid)

  when(state === sWait) {
    when(io.in.valid) {
      when(io.in.bits.hasMultibeatData()) {
        when (io.in.bits.addr_beat === UInt(0)) {
          state := sFill
          count := UInt(1)
          buffer(UInt(tlDataBeats - 1)) := io.in.bits
        }
      } .otherwise {
        state := sValid
        count := UInt(0)
        buffer(UInt(tlDataBeats - 1)) := io.in.bits
      }
    }
  } .elsewhen (state === sValid) {
    assert(!io.in.valid, "I got a valid Grant before I could handle it")
    when(count === UInt(0)) {
      state := sWait
    } .otherwise {
      count := count - UInt(1)
    }
  } .elsewhen (state === sFill) {
    when (io.in.valid) {
      when ((io.in.bits.client_xact_id === buffer(UInt(tlDataBeats - 1)).client_xact_id) && (io.in.bits.addr_beat === count)) {
        when (count === UInt(tlDataBeats - 1)) {
          state := sValid
        } .otherwise {
          count := count + UInt(1)
        }
        buffer(UInt(tlDataBeats - 1) - count) := io.in.bits
      } .otherwise {
        // we lost a packet somewhere, abort this transaction
        state := sWait
      }
    }
  } .otherwise {
    state := sWait
  }

}

class HbwifGrantDeserializerIO(implicit val p: Parameters) extends util.ParameterizedBundle()(p)
  with HasHbwifParameters {

  val serial = (new Decoded8b10bSymbol).asInput
  val grant = Valid(new Grant())

}

class HbwifGrantDeserializer(implicit val p: Parameters) extends Module
  with HasHbwifTileLinkParameters {

  val io = new HbwifGrantDeserializerIO

  val buffer = Reg(Vec(hbwifGrantBytes-1, UInt(width = 8)))
  val checksum = Reg(init = UInt(0, width = 8))
  val valid = Reg(init = Bool(false))
  val count = Reg(UInt(width = log2Up(hbwifGrantBytes)))

  val cat = buffer.asUInt()

  io.grant.bits := (new Grant).fromBits(buffer.asUInt()(hbwifRawGrantBits-1,0))
  io.grant.valid := valid

  val sIdle :: sFill :: Nil = Enum(UInt(), 2)

  val state = Reg(init = sIdle)

  when (state === sIdle) {
    when (io.serial.isData()) {
      state := sFill
    }
    checksum := io.serial.data
    buffer(UInt(0)) := io.serial.data
    count := UInt(1)
    valid := Bool(false)
  } .elsewhen (state === sFill) {
    when (io.serial.isData()) {
      when (count === UInt(hbwifGrantBytes-1)) {
        count := UInt(0)
        when ((checksum + ~io.serial.data + UInt(1)) === UInt(0)) {
          // checksum pass
          valid := Bool(true)
        } .otherwise {
          valid := Bool(false)
        }
        checksum := UInt(0)
      } .otherwise {
        count := count + UInt(1)
        buffer(count) := io.serial.data
        checksum := checksum + io.serial.data
        valid := Bool(false)
      }
    } .otherwise {
      state := sIdle
      count := UInt(0)
      checksum := UInt(0)
      valid := Bool(false)
    }
  } .otherwise {
    state := sIdle
    count := UInt(0)
    checksum := UInt(0)
    valid := Bool(false)
  }

}

class HbwifAcquireTableIO(implicit val p: Parameters) extends util.ParameterizedBundle()(p)
  with HasHbwifTileLinkParameters {

  val in = Decoupled(new Acquire).flip
  val out = Decoupled(new Acquire)

  val retransmitEnable = Bool(INPUT)
  val retransmitCycles = UInt(INPUT, width = log2Up(hbwifMaxRetransmitCycles))

  val clear = Valid(UInt(width = tlClientXactIdBits)).flip

}

class HbwifAcquireTable(implicit val p: Parameters) extends Module
  with HasHbwifTileLinkParameters {

  val io = new HbwifAcquireTableIO

  val sReady :: sFill :: sRetransmit :: Nil = Enum(UInt(), 3)
  val state = Reg(init = sReady)

  val timestamps = Reg(Vec(hbwifBufferDepth, UInt(width = log2Up(hbwifMaxRetransmitCycles))))
  val timeouts = Reg(Vec(hbwifBufferDepth, Bool()))
  val valids = Reg(init = Wire(Vec(hbwifBufferDepth, Bool(false))))
  val xactIdToAddr = Reg(Vec((1 << tlClientXactIdBits), UInt(width = log2Up(hbwifBufferDepth))))

  val table = SeqMem(hbwifBufferDepth * tlDataBeats, new Acquire())
  table.suggestName("Hbwif_table")

  val count = Reg(UInt(width = tlBeatAddrBits))
  val blockAddress = Reg(UInt(width = log2Up(hbwifBufferDepth)))

  val full = valids.reduce(_&_)
  val nextAddress = PriorityEncoder(valids.map(!_))
  val timeoutsMasked = timeouts.zip(valids).map { case (t,v) => t & v }
  val timeout = timeoutsMasked.reduce(_|_)
  val timeoutAddress = PriorityEncoder(timeoutsMasked)

  val retransmitData = Wire(new Acquire())

  io.in.ready := !full & (state =/= sRetransmit)
  io.out.valid := state === sFill | state === sRetransmit | (((state === sReady) | !io.retransmitEnable) & io.in.valid)
  io.out.bits := Mux(state === sRetransmit, retransmitData, io.in.bits)

  // table SRAM stuff
  val wen = (state === sReady | state === sFill) & io.in.fire()
  val waddr = Mux(state === sFill, Cat(blockAddress, count), Cat(nextAddress, UInt(0, width = tlBeatAddrBits)))
  val raddr = Cat(blockAddress, count)

  retransmitData := table.read(raddr, !wen)
  // TODO the timing here is off

  when (wen) { table.write(waddr, io.in.bits) }

  // TODO need to fix TIMEOUT on the same cycle as a clear XXX

  ///////////////////////////// timeouts and timestamps
  when (state === sReady) {
    when (io.in.fire()) {
      (0 until hbwifBufferDepth).foreach { i =>
        when (nextAddress === UInt(i)) {
          timestamps(UInt(i)) := io.retransmitCycles
          timeouts(UInt(i)) := Bool(false)
        } .otherwise {
          timestamps(UInt(i)) := timestamps(UInt(i)) - UInt(1)
          when (timestamps(UInt(i)) === UInt(0)) {
            timeouts(UInt(i)) := Bool(true)
          }
        }
      }
    } .elsewhen (timeout) {
      (0 until hbwifBufferDepth).foreach { i =>
        when (blockAddress === UInt(i)) {
          timestamps(UInt(i)) := io.retransmitCycles
          timeouts(UInt(i)) := Bool(false)
        } .otherwise {
          timestamps(UInt(i)) := timestamps(UInt(i)) - UInt(1)
          when (timestamps(UInt(i)) === UInt(0)) {
            timeouts(UInt(i)) := Bool(true)
          }
        }
      }
    }
  }

  ///////////////////////////// valids
  when (io.retransmitEnable) {
    assert(!io.clear.valid | valids(xactIdToAddr(io.clear.bits)), "Trying to clear a valid bit that isn't set")
    (0 until hbwifBufferDepth).foreach { i =>
      when ((state === sReady) & io.in.fire() & (nextAddress === UInt(i))) {
        valids(UInt(i)) := Bool(true)
      } .elsewhen (io.clear.valid & (xactIdToAddr(io.clear.bits) === UInt(i))) {
        valids(UInt(i)) := Bool(false)
      }
    }
  } .otherwise {
    valids.foreach { _ := Bool(false) }
  }

  ///////////////////////////// xactIdToAddr
  when (state === sReady) {
    when (io.in.fire()) {
      assert(!valids(xactIdToAddr(io.in.bits.client_xact_id)), "Should not get the same client_xact_id before the valid bit is cleared")
      xactIdToAddr(io.in.bits.client_xact_id) := nextAddress
    }
  }

  ///////////////////////////// state
  when (io.retransmitEnable) {
    when (state === sReady) {
      when (io.in.fire()) {
        blockAddress := nextAddress
        count := UInt(1)
        when (!io.in.bits.last()) {
          assert(io.in.bits.addr_beat === UInt(0), "Got a non-zero beat address as first beat")
          state := sFill
        }
      } .elsewhen (timeout) {
        blockAddress := timeoutAddress
        count := UInt(0)
        state := sRetransmit
      }
    } .elsewhen (state === sFill) {
      when (io.in.fire()) {
        assert(io.in.bits.addr_beat === count, "Got a beat out of order")
        count := count + UInt(1)
        when (count === UInt(tlDataBeats-1)) {
          state := sReady
        }
      }
    } .elsewhen (state === sRetransmit) {
      count := count + UInt(1)
      when (count === UInt(tlDataBeats-1)) {
        state := sReady
      }
    }
  } .otherwise {
    state := sReady
  }

}

class HbwifAcquireSerializerIO(implicit val p: Parameters) extends util.ParameterizedBundle()(p)
  with HasHbwifTileLinkParameters {

  val acquire = Decoupled(new Acquire).flip
  val serial = (new Decoded8b10bSymbol).asOutput

}

class HbwifAcquireSerializer(implicit val p: Parameters) extends Module
  with HasHbwifTileLinkParameters {

  val io = new HbwifAcquireSerializerIO

  val buffer = Reg(Vec(hbwifAcquireBytes-1, UInt(width = 8)))
  val checksum = Reg(init = UInt(0, width = 8))
  val count = Reg(init = UInt(0, width = log2Up(hbwifAcquireBytes-1)))

  val sIdle :: sFill :: sChecksum :: Nil = Enum(UInt(), 3)

  val state = Reg(init = sIdle)

  io.acquire.ready := (state === sIdle | state === sChecksum)

  io.serial.valid := (state === sFill | state === sChecksum)
  io.serial.control := Bool(false)
  io.serial.rd := Bool(false) // don't care here

  when (state === sIdle) {
    io.serial.data := buffer(count)
  } .elsewhen (state === sFill) {
    io.serial.data := buffer(count)
  } .elsewhen (state === sChecksum) {
    io.serial.data := ~checksum + UInt(1)
  } .otherwise {
    io.serial.data := buffer(count)
  }

  when (state === sIdle) {
    when (io.acquire.fire()) {
      state := sFill
      count := UInt(0)
      (0 until hbwifAcquireBytes-2).foreach { i => buffer(i) := Cat(UInt(0, width=hbwifAcquirePadBits),io.acquire.bits.asUInt())(i*8+7,i*8) }
    }
  } .elsewhen (state === sFill) {
    when (count === UInt(hbwifAcquireBytes-2)) {
      state := sChecksum
    } .otherwise {
      state := sFill
      count := count + UInt(1)
    }
  } .elsewhen (state === sChecksum) {
    when (io.acquire.fire()) {
      state := sFill
      (0 until hbwifAcquireBytes-2).foreach { i => buffer(i) := Cat(UInt(0, width=hbwifAcquirePadBits),io.acquire.bits.asUInt())(i*8+7,i*8) }
      count := UInt(0)
    } .otherwise {
      state := sIdle
    }
  } .otherwise {
    state := sIdle
  }
}
