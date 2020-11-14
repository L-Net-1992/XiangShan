package xiangshan.backend.exu

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.backend.MDUOpType
import xiangshan.backend.fu.{AbstractDivider, ArrayMultiplier, FunctionUnit, Radix2Divider}

class MulDivExeUnit(hasDiv: Boolean = true) extends Exu(
  exuName = if(hasDiv) "MulDivExeUnit" else "MulExeUnit",
  fuGen = {
    Seq(
      (
        FunctionUnit.multiplier _,
        (x: FunctionUnit) =>
          if(hasDiv) MDUOpType.isMul(x.io.in.bits.uop.ctrl.fuOpType) else true.B
      )
    ) ++ {
      if(hasDiv) Seq(
        (FunctionUnit.divider _, (x: FunctionUnit) => MDUOpType.isDiv(x.io.in.bits.uop.ctrl.fuOpType))
      ) else Nil
    }
  },
  wbIntPriority = 1,
  wbFpPriority = Int.MaxValue
)
{
  val mul = supportedFunctionUnits.collectFirst {
    case m: ArrayMultiplier => m
  }.get

  val div = supportedFunctionUnits.collectFirst {
    case d: AbstractDivider => d
  }.orNull

  // override inputs
  val op  = MDUOpType.getMulOp(func)
  val signext = SignExt(_: UInt, XLEN+1)
  val zeroext = ZeroExt(_: UInt, XLEN+1)
  val mulInputFuncTable = List(
    MDUOpType.mul    -> (zeroext, zeroext),
    MDUOpType.mulh   -> (signext, signext),
    MDUOpType.mulhsu -> (signext, zeroext),
    MDUOpType.mulhu  -> (zeroext, zeroext)
  )

  mul.io.in.bits.src(0) := LookupTree(
    op,
    mulInputFuncTable.map(p => (p._1(1,0), p._2._1(src1)))
  )
  mul.io.in.bits.src(1) := LookupTree(
    op,
    mulInputFuncTable.map(p => (p._1(1,0), p._2._2(src2)))
  )

  val isW = MDUOpType.isW(func)
  val isH = MDUOpType.isH(func)
  mul.ctrl.isW := isW
  mul.ctrl.isHi := isH
  mul.ctrl.sign := DontCare

  val isDivSign = MDUOpType.isDivSign(func)
  val divInputFunc = (x: UInt) => Mux(
    isW,
    Mux(isDivSign,
      SignExt(x(31,0), XLEN),
      ZeroExt(x(31,0), XLEN)
    ),
    x
  )
  if(hasDiv){
    div.io.in.bits.src(0) := divInputFunc(src1)
    div.io.in.bits.src(1) := divInputFunc(src2)
    div.ctrl.isHi := isH
    div.ctrl.isW := isW
    div.ctrl.sign := isDivSign
  }

  XSDebug(io.in.valid, "In(%d %d) Out(%d %d) Redirect:(%d %d %d) brTag:%x\n",
    io.in.valid, io.in.ready,
    io.out.valid, io.out.ready,
    io.redirect.valid,
    io.redirect.bits.isException,
    io.redirect.bits.isFlushPipe,
    io.redirect.bits.brTag.value
  )
  XSDebug(io.in.valid, "src1:%x src2:%x pc:%x\n", src1, src2, io.in.bits.uop.cf.pc)
  XSDebug(io.out.valid, "Out(%d %d) res:%x pc:%x\n",
    io.out.valid, io.out.ready, io.out.bits.data, io.out.bits.uop.cf.pc
  )
}

class MulExeUnit extends MulDivExeUnit(hasDiv = false)