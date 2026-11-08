package org.futo.inputmethod.latin.uix.actions;
import java.util.*;
import org.futo.inputmethod.latin.uix.actions.OrderedActionIds;
public class OrderedActionIdsTest {
 static List<String> l(String... s) { return Arrays.asList(s); }
 static void eq(Object a,Object b) { if (!a.equals(b)) throw new AssertionError(a+" != "+b); }
 @org.junit.Test public void orderedHiddenAndMigrationInvariants() {
  OrderedActionIds migrated=OrderedActionIds.migrate(l("emoji"),l("voice"),l("clip","emoji"),l("gif","voice"),l("debug"),l("emoji","voice","clip","gif","debug"));
  eq(migrated.visible,l("emoji","voice","clip","gif"));eq(migrated.hidden,l("debug"));
  OrderedActionIds m=OrderedActionIds.normalize(l("emoji","clip","emoji","unknown","debug"),l("debug","debug"),l("clip","emoji","debug","gif"));
  eq(m.visible,l("emoji","clip")); eq(m.hidden,l("debug","gif"));
  OrderedActionIds again=OrderedActionIds.normalize(m.visible,m.hidden,l("clip","emoji","debug","gif"));eq(again.visible,m.visible);eq(again.hidden,m.hidden);
  eq(m.move("clip",0).visible,l("clip","emoji"));eq(m.move("clip",99).visible,l("emoji","clip"));
  OrderedActionIds hidden=m.hide("emoji").hide("clip");eq(hidden.visible,l());eq(hidden.hidden,l("debug","gif","emoji","clip"));
  eq(hidden.show("emoji",0).visible,l("emoji"));eq(hidden.show("emoji",0).hidden,l("debug","gif","clip"));
  eq(m.hide("missing").visible,m.visible);eq(m.show("missing",0).hidden,m.hidden);
  try { m.visible.add("x");throw new AssertionError("mutable"); } catch(UnsupportedOperationException expected) {}
  for(int mask=0;mask<16;mask++) {
   List<String> v=new ArrayList<>(), h=new ArrayList<>(); List<String> known=l("a","b","c","d");
   for(int i=0;i<4;i++) {if((mask&(1<<i))!=0)v.add(known.get(i));else h.add(known.get(i));}
   OrderedActionIds n=OrderedActionIds.normalize(v,h,known);Set<String> union=new HashSet<>(n.visible);union.addAll(n.hidden);
   eq(union,new HashSet<>(known));eq(n.visible.size()+n.hidden.size(),4);
  }
  System.out.println("PASS pure ordered/hidden invariants: normalize, dedup, hidden-wins, unknown IDs, missing IDs, reorder, hide/show, empty visible, bounds, immutability, idempotence, all16partitions. Not UI or persistence proof.");
 }
}
