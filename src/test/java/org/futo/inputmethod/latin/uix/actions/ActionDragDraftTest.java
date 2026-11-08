package org.futo.inputmethod.latin.uix.actions;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ActionDragDraftTest {
 private List<String> l(String...x){return Arrays.asList(x);}
 private OrderedActionIds initial(){return OrderedActionIds.normalize(l("a","b"),l("c"),l("a","b","c"));}
 @Test public void completedDropCommitsOnce(){ActionDragDraft d=new ActionDragDraft(initial());d.move("b",0);assertEquals(l("b","a"),d.drop().visible);assertNull(d.drop());}
 @Test public void cancelledDragDiscards(){ActionDragDraft d=new ActionDragDraft(initial());d.hide("a");d.cancel();assertEquals(l("a","b"),d.preview().visible);assertNull(d.drop());}
 @Test public void noOpDropDoesNotWrite(){ActionDragDraft d=new ActionDragDraft(initial());d.move("b",0);d.move("b",1);assertNull(d.drop());}
 @Test public void hiddenDropKeepsPartition(){ActionDragDraft d=new ActionDragDraft(initial());d.hide("a");OrderedActionIds n=d.drop();assertEquals(l("b"),n.visible);assertEquals(l("c","a"),n.hidden);}
}
