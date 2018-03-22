package hbwif2.tilelink

import hbwif2._
import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._

trait TLPacketizerLike {

    val tlTypeWidth = 3

    def headerWidth(x: TLChannel): Int = {
        x match {
            case a: TLBundleA => { tlTypeWidth + 3 + List(TLAtomics.width, TLPermissions.aWidth, TLHints.width).max + a.params.sizeBits + a.params.sourceBits + a.params.addressBits } // mask is separate
            case b: TLBundleB => { tlTypeWidth + 3 + TLPermissions.bdWidth + b.params.sizeBits + b.params.sourceBits + b.params.addressBits } // mask is separate
            case c: TLBundleC => { tlTypeWidth + 3 + TLPermissions.cWidth + c.params.sizeBits + c.params.sourceBits + c.params.addressBits }  // ignore error
            case d: TLBundleD => { tlTypeWidth + 3 + TLPermissions.bdWidth + d.params.sizeBits + d.params.sourceBits + d.params.sinkBits }    // ignore error
            case e: TLBundleE => { tlTypeWidth + e.params.sinkBits }
        }
    }

    val typeA = 0.U(tlTypeWidth.W)
    val typeB = 1.U(tlTypeWidth.W)
    val typeC = 2.U(tlTypeWidth.W)
    val typeD = 3.U(tlTypeWidth.W)
    val typeE = 4.U(tlTypeWidth.W)

    def div8Ceil(x: Int): Int = (x + 7)/8

    def tlFromBuffer[T <: TLChannel](edge: TLEdge, x: T, buf: UInt, error: Bool): DecoupledIO[T] = {
        val w = buf.getWidth - tlTypeWidth
        (x match {
            case a: TLBundleA => {
                val out = Wire(Decoupled(new TLBundleA(edge.bundle)))
                val bits = out.bits
                val padBits = (8 - (headerWidth(bits) % 8)) % 8
                (List(bits.opcode, bits.param, bits.size, bits.source, bits.address) ++
                    (if (padBits > 0) List(Wire(UInt(padBits.W))) else List()) ++
                    List(bits.mask, bits.data)).foldLeft(w) { (left, sig) =>
                    if (sig.getWidth > 0) {
                        sig := buf(left - 1, left - sig.getWidth)
                        left - sig.getWidth
                    } else {
                        left
                    }
                }
                out
            }
            case b: TLBundleB => {
                val out = Wire(Decoupled(new TLBundleB(edge.bundle)))
                val bits = out.bits
                val padBits = (8 - (headerWidth(bits) % 8)) % 8
                (List(bits.opcode, bits.param, bits.size, bits.source, bits.address) ++
                    (if (padBits > 0) List(Wire(UInt(padBits.W))) else List()) ++
                    List(bits.mask, bits.data)).foldLeft(w) { (left, sig) =>
                    if (sig.getWidth > 0) {
                        sig := buf(left - 1, left - sig.getWidth)
                        left - sig.getWidth
                    } else {
                        left
                    }
                }
                out
            }
            case c: TLBundleC => {
                val out = Wire(Decoupled(new TLBundleC(edge.bundle)))
                val bits = out.bits
                val padBits = (8 - (headerWidth(bits) % 8)) % 8
                (List(bits.opcode, bits.param, bits.size, bits.source, bits.address) ++
                    (if (padBits > 0) List(Wire(UInt(padBits.W))) else List()) ++
                    List(bits.data)).foldLeft(w) { (left, sig) =>
                    if (sig.getWidth > 0) {
                        sig := buf(left - 1, left - sig.getWidth)
                        left - sig.getWidth
                    } else {
                        left
                    }
                }
                bits.error := error
                out
            }
            case d: TLBundleD => {
                val out = Wire(Decoupled(new TLBundleD(edge.bundle)))
                val bits = out.bits
                val padBits = (8 - (headerWidth(bits) % 8)) % 8
                (List(bits.opcode, bits.param, bits.size, bits.source, bits.sink) ++
                    (if (padBits > 0) List(Wire(UInt(padBits.W))) else List()) ++
                    List(bits.data)).foldLeft(w) { (left, sig) =>
                    if (sig.getWidth > 0) {
                        sig := buf(left - 1, left - sig.getWidth)
                        left - sig.getWidth
                    } else {
                        left
                    }
                }
                bits.error := error
                out
            }
            case e: TLBundleE => {
                val out = Wire(Decoupled(new TLBundleE(edge.bundle)))
                out.bits.sink := buf(w - 1, w - out.bits.sink.getWidth)
                out
            }
        }).asInstanceOf[DecoupledIO[T]]
    }

    // TODO write this more cleanly
    def tlToBuffer[T <: TLChannel](edge: TLEdge, x: T, padTo: Int): UInt = {

        x match {
            case a: TLBundleA => {
                val pad1 = (8 - (headerWidth(a) % 8)) % 8
                val pad2 = padTo - headerWidth(a) - pad1 - a.params.dataBits/8 - a.params.dataBits
                Cat((if (pad1 > 0)
                    Cat(typeA, a.opcode, a.param, a.size, a.source, a.address, 0.U(pad1.W)) else
                    Cat(typeA, a.opcode, a.param, a.size, a.source, a.address)),
                    (if (pad2 > 0)
                    Cat(a.mask, a.data, 0.U(pad2.W)) else
                    Cat(a.mask, a.data)))
            }
            case b: TLBundleB => {
                val pad1 = (8 - (headerWidth(b) % 8)) % 8
                val pad2 = padTo - headerWidth(b) - pad1 - b.params.dataBits/8 - b.params.dataBits
                Cat((if (pad1 > 0)
                    Cat(typeB, b.opcode, b.param, b.size, b.source, b.address, 0.U(pad1.W)) else
                    Cat(typeB, b.opcode, b.param, b.size, b.source, b.address)),
                    (if (pad2 > 0)
                    Cat(b.mask, b.data, 0.U(pad2.W)) else
                    Cat(b.mask, b.data)))
            }
            case c: TLBundleC => {
                val pad1 = (8 - (headerWidth(c) % 8)) % 8
                val pad2 = padTo - headerWidth(c) - pad1 - c.params.dataBits
                Cat((if (pad1 > 0)
                    Cat(typeC, c.opcode, c.param, c.size, c.source, c.address, 0.U(pad1.W)) else
                    Cat(typeC, c.opcode, c.param, c.size, c.source, c.address)),
                    (if (pad2 > 0)
                    Cat(c.data, 0.U(pad2.W)) else
                    c.data))
            }
            case d: TLBundleD => {
                val pad1 = (8 - (headerWidth(d) % 8)) % 8
                val pad2 = padTo - headerWidth(d) - pad1 - d.params.dataBits
                Cat((if (pad1 > 0)
                    Cat(typeD, d.opcode, d.param, d.size, d.source, d.sink, 0.U(pad1.W)) else
                    Cat(typeD, d.opcode, d.param, d.size, d.source, d.sink)),
                    (if (pad2 > 0)
                    Cat(d.data, 0.U(pad2.W)) else
                    d.data))
            }
            case e: TLBundleE => {
                val pad = 0.U((padTo - headerWidth(e)).W)
                Cat(typeE, e.sink, pad)
            }
        }
    }

    def typeFromBuffer(buf: UInt): (UInt, UInt) = (buf(buf.getWidth - 1, buf.getWidth - tlTypeWidth), buf(buf.getWidth - tlTypeWidth - 1, buf.getWidth - tlTypeWidth - 3))

    def getNumSymbolsFromType(aceEdge: TLEdge, bdEdge: TLEdge, tlType: UInt, opcode: UInt, first: Bool): UInt = {
        val ret = Wire(UInt())
        when (tlType === typeA) {
            val a = Wire(new TLBundleA(aceEdge.bundle))
            a := a.fromBits(0.U)
            a.opcode := opcode
            ret := getNumSymbols(aceEdge, a, first)
        } .elsewhen (tlType === typeB) {
            val b = Wire(new TLBundleB(bdEdge.bundle))
            b := b.fromBits(0.U)
            b.opcode := opcode
            ret := getNumSymbols(bdEdge, b, first)
        } .elsewhen (tlType === typeC) {
            val c = Wire(new TLBundleC(aceEdge.bundle))
            c := c.fromBits(0.U)
            c.opcode := opcode
            ret := getNumSymbols(aceEdge, c, first)
        } .elsewhen (tlType === typeD) {
            val d = Wire(new TLBundleD(bdEdge.bundle))
            d := d.fromBits(0.U)
            d.opcode := opcode
            ret := getNumSymbols(bdEdge, d, first)
        } .otherwise {
            val e = Wire(new TLBundleE(aceEdge.bundle))
            e := e.fromBits(0.U)
            ret := getNumSymbols(aceEdge, e, first)
        }
        ret
    }

    // Ignore error signals here, we'll generate our own
    // TODO we can avoid sending mask in some cases
    def getNumSymbols(edge: TLEdge, x: TLChannel, first: Bool): UInt = {
        Mux(first, div8Ceil(headerWidth(x)).U, 0.U) + Mux(edge.hasData(x), (edge.bundle.dataBits/8).U, 0.U) + (x match {
            case a: TLBundleA => { div8Ceil(edge.bundle.dataBits/8).U }
            case b: TLBundleB => { div8Ceil(edge.bundle.dataBits/8).U }
            case _ => { 0.U }
        })
    }

    def tlResponseMap(x: TLChannel): UInt = 1.U

    /*
    def tlResponseMap(x: TLChannel): UInt = {
        // TODO
        assert(false)
        x match {
            case a: TLBundleA => {
                MuxLookup(a.opcode, 0.U, Seq(
                    (TLMessages.PutFullData,    1.U),
                    (TLMessages.PutPartialData, 1.U),
                    (TLMessages.ArithmeticData, X.U),
                    (TLMessages.LogicalData,    X.U),
                    (TLMessages.Get,            X.U),
                    (TLMessages.Hint,           X.U),
                    (TLMessages.AcquireBlock,   X.U),
                    (TLMessages.AcquirePerm,    X.U)
                ))
            }
            case b: TLBundleB => {
                MuxLookup(a.opcode, 0.U, Seq(
                    (TLMessages.PutFullData,    X.U),
                    (TLMessages.PutPartialData, X.U),
                    (TLMessages.ArithmeticData, X.U),
                    (TLMessages.LogicalData,    X.U),
                    (TLMessages.Get,            X.U),
                    (TLMessages.Hint,           X.U),
                    (TLMessages.Probe,          X.U)
                ))
            }
            case c: TLBundleC => {
                MuxLookup(a.opcode, 0.U, Seq(
                    (TLMessages.AccessAck,      X.U),
                    (TLMessages.AccessAckData,  X.U),
                    (TLMessages.HintAck,        X.U),
                    (TLMessages.ProbeAck,       X.U),
                    (TLMessages.ProbeAckData,   X.U),
                    (TLMessages.Hint,           X.U),
                    (TLMessages.Probe,          X.U)
                ))
            }
            case d: TLBundleD => {
                0.U
            }
            case e: TLBundleE => {
                0.U
            }
        }
    }
    */
}
