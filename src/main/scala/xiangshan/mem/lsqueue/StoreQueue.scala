package xiangshan.mem

import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.cache._
import xiangshan.cache.{DCacheWordIO, DCacheLineIO, TlbRequestIO, MemoryOpConstants}
import xiangshan.backend.LSUOpType
import xiangshan.backend.roq.RoqPtr


class SqPtr extends CircularQueuePtr(SqPtr.StoreQueueSize) { }

object SqPtr extends HasXSParameter {
  def apply(f: Bool, v: UInt): SqPtr = {
    val ptr = Wire(new SqPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

class SqEnqIO extends XSBundle {
  val canAccept = Output(Bool())
  val lqCanAccept = Input(Bool())
  val needAlloc = Vec(RenameWidth, Input(Bool()))
  val req = Vec(RenameWidth, Flipped(ValidIO(new MicroOp)))
  val resp = Vec(RenameWidth, Output(new SqPtr))
}

// Store Queue
class StoreQueue extends XSModule with HasDCacheParameters with HasCircularQueuePtrHelper {
  val io = IO(new Bundle() {
    val enq = new SqEnqIO
    val brqRedirect = Input(Valid(new Redirect))
    val storeIn = Vec(StorePipelineWidth, Flipped(Valid(new LsPipelineBundle)))
    val sbuffer = Vec(StorePipelineWidth, Decoupled(new DCacheWordReq))
    val mmioStout = DecoupledIO(new ExuOutput) // writeback uncached store
    val forward = Vec(LoadPipelineWidth, Flipped(new LoadForwardQueryIO))
    val commits = Flipped(new RoqCommitIO)
    val uncache = new DCacheWordIO
    val roqDeqPtr = Input(new RoqPtr)
    // val refill = Flipped(Valid(new DCacheLineReq ))
    val exceptionAddr = new ExceptionAddrIO
  })

  val uop = Reg(Vec(StoreQueueSize, new MicroOp))
  // val data = Reg(Vec(StoreQueueSize, new LsqEntry))
  val dataModule = Module(new LSQueueData(StoreQueueSize, StorePipelineWidth))
  dataModule.io := DontCare
  val allocated = RegInit(VecInit(List.fill(StoreQueueSize)(false.B))) // sq entry has been allocated
  val datavalid = RegInit(VecInit(List.fill(StoreQueueSize)(false.B))) // non-mmio data is valid
  val writebacked = RegInit(VecInit(List.fill(StoreQueueSize)(false.B))) // inst has been writebacked to CDB
  val commited = Reg(Vec(StoreQueueSize, Bool())) // inst has been commited by roq
  val pending = Reg(Vec(StoreQueueSize, Bool())) // mmio pending: inst is an mmio inst, it will not be executed until it reachs the end of roq

  require(StoreQueueSize > RenameWidth)
  val enqPtrExt = RegInit(VecInit((0 until RenameWidth).map(_.U.asTypeOf(new SqPtr))))
  val deqPtrExt = RegInit(VecInit((0 until StorePipelineWidth).map(_.U.asTypeOf(new SqPtr))))
  val enqPtr = enqPtrExt(0).value
  val deqPtr = deqPtrExt(0).value

  val tailMask = UIntToMask(deqPtr, StoreQueueSize)
  val headMask = UIntToMask(enqPtr, StoreQueueSize)

  /**
    * Enqueue at dispatch
    *
    * Currently, StoreQueue only allows enqueue when #emptyEntries > RenameWidth(EnqWidth)
    */
  val validEntries = distanceBetween(enqPtrExt(0), deqPtrExt(0))
  val firedDispatch = io.enq.req.map(_.valid)
  io.enq.canAccept := validEntries <= (StoreQueueSize - RenameWidth).U
  XSDebug(p"(ready, valid): ${io.enq.canAccept}, ${Binary(Cat(firedDispatch))}\n")
  for (i <- 0 until RenameWidth) {
    val offset = if (i == 0) 0.U else PopCount(io.enq.needAlloc.take(i))
    val sqIdx = enqPtrExt(offset)
    val index = sqIdx.value
    when (io.enq.req(i).valid && io.enq.canAccept && io.enq.lqCanAccept && !io.brqRedirect.valid) {
      uop(index) := io.enq.req(i).bits
      allocated(index) := true.B
      datavalid(index) := false.B
      writebacked(index) := false.B
      commited(index) := false.B
      pending(index) := false.B
    }
    io.enq.resp(i) := sqIdx
  }

  when (Cat(firedDispatch).orR && io.enq.canAccept && io.enq.lqCanAccept && !io.brqRedirect.valid) {
    val enqNumber = PopCount(firedDispatch)
    enqPtrExt := VecInit(enqPtrExt.map(_ + enqNumber))
    XSInfo("dispatched %d insts to sq\n", enqNumber)
  }

  /**
    * Writeback store from store units
    *
    * Most store instructions writeback to regfile in the previous cycle.
    * However,
    *   (1) For an mmio instruction with exceptions, we need to mark it as datavalid
    * (in this way it will trigger an exception when it reaches ROB's head)
    * instead of pending to avoid sending them to lower level.
    *   (2) For an mmio instruction without exceptions, we mark it as pending.
    * When the instruction reaches ROB's head, StoreQueue sends it to uncache channel.
    * Upon receiving the response, StoreQueue writes back the instruction 
    * through arbiter with store units. It will later commit as normal.
    */
  for (i <- 0 until StorePipelineWidth) {
    dataModule.io.wb(i).wen := false.B
    when(io.storeIn(i).fire()) {
      val stWbIndex = io.storeIn(i).bits.uop.sqIdx.value
      val hasException = io.storeIn(i).bits.uop.cf.exceptionVec.asUInt.orR
      val hasWritebacked = !io.storeIn(i).bits.mmio || hasException
      datavalid(stWbIndex) := hasWritebacked
      writebacked(stWbIndex) := hasWritebacked
      pending(stWbIndex) := !hasWritebacked // valid mmio require

      val storeWbData = Wire(new LsqEntry)
      storeWbData := DontCare
      storeWbData.paddr := io.storeIn(i).bits.paddr
      storeWbData.vaddr := io.storeIn(i).bits.vaddr
      storeWbData.mask := io.storeIn(i).bits.mask
      storeWbData.data := io.storeIn(i).bits.data
      storeWbData.mmio := io.storeIn(i).bits.mmio
      storeWbData.exception := io.storeIn(i).bits.uop.cf.exceptionVec.asUInt

      dataModule.io.wbWrite(i, stWbIndex, storeWbData)
      dataModule.io.wb(i).wen := true.B

      XSInfo("store write to sq idx %d pc 0x%x vaddr %x paddr %x data %x mmio %x roll %x exc %x\n",
        io.storeIn(i).bits.uop.sqIdx.value,
        io.storeIn(i).bits.uop.cf.pc,
        io.storeIn(i).bits.vaddr,
        io.storeIn(i).bits.paddr,
        io.storeIn(i).bits.data,
        io.storeIn(i).bits.mmio,
        io.storeIn(i).bits.rollback,
        io.storeIn(i).bits.uop.cf.exceptionVec.asUInt
        )
    }
  }

  /**
    * load forward query
    *
    * Check store queue for instructions that is older than the load.
    * The response will be valid at the next cycle after req.
    */
  // check over all lq entries and forward data from the first matched store
  for (i <- 0 until LoadPipelineWidth) {
    io.forward(i).forwardMask := 0.U(8.W).asBools
    io.forward(i).forwardData := DontCare

    // Compare deqPtr (deqPtr) and forward.sqIdx, we have two cases:
    // (1) if they have the same flag, we need to check range(tail, sqIdx)
    // (2) if they have different flags, we need to check range(tail, LoadQueueSize) and range(0, sqIdx)
    // Forward1: Mux(same_flag, range(tail, sqIdx), range(tail, LoadQueueSize))
    // Forward2: Mux(same_flag, 0.U,                   range(0, sqIdx)    )
    // i.e. forward1 is the target entries with the same flag bits and forward2 otherwise
    val differentFlag = deqPtrExt(0).flag =/= io.forward(i).sqIdx.flag
    val forwardMask = UIntToMask(io.forward(i).sqIdx.value, StoreQueueSize)
    val storeWritebackedVec = WireInit(VecInit(Seq.fill(StoreQueueSize)(false.B)))
    for (j <- 0 until StoreQueueSize) {
      storeWritebackedVec(j) := datavalid(j) && allocated(j) // all datavalid terms need to be checked
    }
    val needForward1 = Mux(differentFlag, ~tailMask, tailMask ^ forwardMask) & storeWritebackedVec.asUInt
    val needForward2 = Mux(differentFlag, forwardMask, 0.U(StoreQueueSize.W)) & storeWritebackedVec.asUInt

    XSDebug(p"$i f1 ${Binary(needForward1)} f2 ${Binary(needForward2)} " +
      p"sqIdx ${io.forward(i).sqIdx} pa ${Hexadecimal(io.forward(i).paddr)}\n"
    )

    // do real fwd query
    dataModule.io.forwardQuery(
      channel = i,
      paddr = io.forward(i).paddr,
      needForward1 = needForward1,
      needForward2 = needForward2
    )

    io.forward(i).forwardMask := dataModule.io.forward(i).forwardMask
    io.forward(i).forwardData := dataModule.io.forward(i).forwardData
  }

  /**
    * Memory mapped IO / other uncached operations
    *
    * States:
    * (1) writeback from store units: mark as pending
    * (2) when they reach ROB's head, they can be sent to uncache channel
    * (3) response from uncache channel: mark as datavalid
    * (4) writeback to ROB (and other units): mark as writebacked
    * (5) ROB commits the instruction: same as normal instructions
    */
  //(2) when they reach ROB's head, they can be sent to uncache channel
  val commitType = io.commits.uop(0).ctrl.commitType
  io.uncache.req.valid := pending(deqPtr) && allocated(deqPtr) &&
    commitType === CommitType.STORE &&
    io.roqDeqPtr === uop(deqPtr).roqIdx &&
    !io.commits.isWalk

  io.uncache.req.bits.cmd  := MemoryOpConstants.M_XWR
  io.uncache.req.bits.addr := dataModule.io.rdata(deqPtr).paddr
  io.uncache.req.bits.data := dataModule.io.rdata(deqPtr).data
  io.uncache.req.bits.mask := dataModule.io.rdata(deqPtr).mask

  io.uncache.req.bits.meta.id       := DontCare // TODO: // FIXME
  io.uncache.req.bits.meta.vaddr    := DontCare
  io.uncache.req.bits.meta.paddr    := dataModule.io.rdata(deqPtr).paddr
  io.uncache.req.bits.meta.uop      := uop(deqPtr)
  io.uncache.req.bits.meta.mmio     := true.B
  io.uncache.req.bits.meta.tlb_miss := false.B
  io.uncache.req.bits.meta.mask     := dataModule.io.rdata(deqPtr).mask
  io.uncache.req.bits.meta.replay   := false.B

  when(io.uncache.req.fire()){
    pending(deqPtr) := false.B

    XSDebug(
      p"uncache req: pc ${Hexadecimal(uop(deqPtr).cf.pc)} " +
      p"addr ${Hexadecimal(io.uncache.req.bits.addr)} " +
      p"data ${Hexadecimal(io.uncache.req.bits.data)} " +
      p"op ${Hexadecimal(io.uncache.req.bits.cmd)} " +
      p"mask ${Hexadecimal(io.uncache.req.bits.mask)}\n"
    )
  }

  // (3) response from uncache channel: mark as datavalid
  io.uncache.resp.ready := true.B
  when (io.uncache.resp.fire()) {
    datavalid(deqPtr) := true.B
  }

  // (4) writeback to ROB (and other units): mark as writebacked
  io.mmioStout.valid := allocated(deqPtr) && datavalid(deqPtr) && !writebacked(deqPtr)
  io.mmioStout.bits.uop := uop(deqPtr)
  io.mmioStout.bits.uop.sqIdx := deqPtrExt(0)
  io.mmioStout.bits.uop.cf.exceptionVec := dataModule.io.rdata(deqPtr).exception.asBools
  io.mmioStout.bits.data := dataModule.io.rdata(deqPtr).data
  io.mmioStout.bits.redirectValid := false.B
  io.mmioStout.bits.redirect := DontCare
  io.mmioStout.bits.brUpdate := DontCare
  io.mmioStout.bits.debug.isMMIO := true.B
  io.mmioStout.bits.fflags := DontCare
  when (io.mmioStout.fire()) {
    writebacked(deqPtr) := true.B
    allocated(deqPtr) := false.B
    deqPtrExt := VecInit(deqPtrExt.map(_ + 1.U))
  }

  /**
    * ROB commits store instructions (mark them as commited)
    *
    * (1) When store commits, mark it as commited.
    * (2) They will not be cancelled and can be sent to lower level.
    */
  for (i <- 0 until CommitWidth) {
    val storeCommit = !io.commits.isWalk && io.commits.valid(i) && io.commits.uop(i).ctrl.commitType === CommitType.STORE
    when (storeCommit) {
      commited(io.commits.uop(i).sqIdx.value) := true.B
      XSDebug("store commit %d: idx %d %x\n", i.U, io.commits.uop(i).sqIdx.value, io.commits.uop(i).cf.pc)
    }
  }

  // Commited stores will not be cancelled and can be sent to lower level.
  // remove retired insts from sq, add retired store to sbuffer
  for (i <- 0 until StorePipelineWidth) {
    val ptr = deqPtrExt(i).value
    val mmio = dataModule.io.rdata(ptr).mmio
    io.sbuffer(i).valid := allocated(ptr) && commited(ptr) && !mmio
    io.sbuffer(i).bits.cmd  := MemoryOpConstants.M_XWR
    io.sbuffer(i).bits.addr := dataModule.io.rdata(ptr).paddr
    io.sbuffer(i).bits.data := dataModule.io.rdata(ptr).data
    io.sbuffer(i).bits.mask := dataModule.io.rdata(ptr).mask
    io.sbuffer(i).bits.meta          := DontCare
    io.sbuffer(i).bits.meta.tlb_miss := false.B
    io.sbuffer(i).bits.meta.uop      := DontCare
    io.sbuffer(i).bits.meta.mmio     := mmio
    io.sbuffer(i).bits.meta.mask     := dataModule.io.rdata(ptr).mask

    when (io.sbuffer(i).fire()) {
      allocated(ptr) := false.B
      XSDebug("sbuffer "+i+" fire: ptr %d\n", ptr)
    }
  }
  // note that sbuffer will not accept req(1) if req(0) is not accepted.
  when (Cat(io.sbuffer.map(_.fire())).orR) {
    val stepForward = Mux(io.sbuffer(1).fire(), 2.U, 1.U)
    deqPtrExt := VecInit(deqPtrExt.map(_ + stepForward))
    when (io.sbuffer(1).fire()) {
      assert(io.sbuffer(0).fire())
    }
  }

  // Read vaddr for mem exception
  io.exceptionAddr.vaddr := dataModule.io.rdata(io.exceptionAddr.lsIdx.sqIdx.value).vaddr

  // misprediction recovery / exception redirect
  // invalidate sq term using robIdx
  val needCancel = Wire(Vec(StoreQueueSize, Bool()))
  for (i <- 0 until StoreQueueSize) {
    needCancel(i) := uop(i).roqIdx.needFlush(io.brqRedirect) && allocated(i) && !commited(i)
    when (needCancel(i)) {
        allocated(i) := false.B
    }
  }
  // we recover the pointers in the next cycle after redirect
  val lastCycleRedirectValid = RegNext(io.brqRedirect.valid)
  val needCancelCount = PopCount(RegNext(needCancel))
  when (lastCycleRedirectValid) {
    enqPtrExt := VecInit(enqPtrExt.map(_ - needCancelCount))
  }

  // debug info
  XSDebug("enqPtrExt %d:%d deqPtrExt %d:%d\n", enqPtrExt(0).flag, enqPtr, deqPtrExt(0).flag, deqPtr)

  def PrintFlag(flag: Bool, name: String): Unit = {
    when(flag) {
      XSDebug(false, true.B, name)
    }.otherwise {
      XSDebug(false, true.B, " ")
    }
  }

  for (i <- 0 until StoreQueueSize) {
    if (i % 4 == 0) XSDebug("")
    XSDebug(false, true.B, "%x [%x] ", uop(i).cf.pc, dataModule.io.rdata(i).paddr)
    PrintFlag(allocated(i), "a")
    PrintFlag(allocated(i) && datavalid(i), "v")
    PrintFlag(allocated(i) && writebacked(i), "w")
    PrintFlag(allocated(i) && commited(i), "c")
    PrintFlag(allocated(i) && pending(i), "p")
    XSDebug(false, true.B, " ")
    if (i % 4 == 3 || i == StoreQueueSize - 1) XSDebug(false, true.B, "\n")
  }

}
