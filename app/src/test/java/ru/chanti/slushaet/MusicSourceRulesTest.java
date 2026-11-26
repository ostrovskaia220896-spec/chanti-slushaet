package ru.chanti.slushaet;

import org.junit.Test;
import static org.junit.Assert.*;

public class MusicSourceRulesTest {
    @Test public void newNotificationWinsOverFrozenSession(){
        MusicSourceRules r=new MusicSourceRules();
        assertFalse(r.useNotification("Улицы","МИ","Улицы","МИ"));
        assertTrue(r.useNotification("Улицы","МИ","Так мало","7линия"));
        assertTrue(r.useNotification("Улицы","МИ","Так мало","7линия"));
        assertFalse(r.useNotification("Так мало","7линия","Так мало","7линия"));
    }
    @Test public void newSessionWinsOverFrozenNotification(){
        MusicSourceRules r=new MusicSourceRules();
        assertFalse(r.useNotification("A","artist","A","artist"));
        assertFalse(r.useNotification("B","artist","A","artist"));
        assertFalse(r.useNotification("B","artist","A","artist"));
    }
    @Test public void missingNotificationFallsBackToSession(){
        MusicSourceRules r=new MusicSourceRules();
        assertTrue(r.useNotification("A","artist","B","artist"));
        assertFalse(r.useNotification("A","artist","",""));
    }
    @Test public void missingSessionCanUseNotification(){
        MusicSourceRules r=new MusicSourceRules();
        assertTrue(r.useNotification("","","B","artist"));
    }
}
