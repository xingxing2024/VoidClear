package net.voidclear.v2.runtime;
public final class SweepBudgetCheck {
 public static void main(String[] args) {
  var b=new SweepBudget(true,64,4,5_000_000L);
  assert b.limit()==4;
  b.record(100_000L);assert b.limit()==5;
  b.record(20_000_000L);assert b.limit()==4;
  for(int i=0;i<50;i++)b.record(1);assert b.limit()==64;
  b.record(10_000_000L);assert b.limit()==32;
  var fixed=new SweepBudget(false,12,4,5_000_000L);fixed.record(Long.MAX_VALUE);assert fixed.limit()==12;
  var bounded=new SweepBudget(true,2,16,1);assert bounded.limit()==2;
  System.out.println("SweepBudgetCheck: ALL OK");
 }
}
