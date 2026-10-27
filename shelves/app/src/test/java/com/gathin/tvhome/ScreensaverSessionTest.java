package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreensaverSessionTest {
    private static final String CLOCK="clock",GOOGLE="google";
    private static class Fake implements ScreensaverSession.Host {
        String current=CLOCK,original;boolean pending,armed,launched,failLaunch,failRestore,failArm;Runnable started;
        public boolean pending(){return pending;}public String original(){return original;}public String current(){return current;}
        public void save(String s){original=s;pending=true;}
        public void select(String s){if(failRestore&&!GOOGLE.equals(s))throw new IllegalStateException("denied");current=s;}
        public void armRecovery(){if(failArm)throw new IllegalStateException("no alarm");armed=true;}
        public void watchStart(Runnable r){started=r;}
        public void launch(){if(failLaunch)throw new IllegalStateException("missing activity");launched=true;}
        public void clear(){pending=false;armed=false;started=null;}
    }
    private ScreensaverSession session(Fake f){return new ScreensaverSession(f,GOOGLE);}
    @Test public void slowStartupDoesNotRestoreUntilSystemConfirmsIt(){Fake f=new Fake();session(f).start();assertEquals(GOOGLE,f.current);assertTrue(f.armed);assertTrue(f.pending);assertTrue(f.launched);f.started.run();assertEquals(CLOCK,f.current);assertFalse(f.pending);assertFalse(f.armed);}
    @Test public void failedLaunchRestoresOriginal(){Fake f=new Fake();f.failLaunch=true;try{session(f).start();fail();}catch(IllegalStateException expected){}assertEquals(CLOCK,f.current);assertFalse(f.pending);}
    @Test public void missingRecoveryAlarmDoesNotChangeSelection(){Fake f=new Fake();f.failArm=true;try{session(f).start();fail();}catch(IllegalStateException expected){}assertEquals(CLOCK,f.current);assertFalse(f.launched);}
    @Test public void newProcessRecoversSavedSelection(){Fake f=new Fake();session(f).start();f.started=null;new ScreensaverSession(f,GOOGLE).restore();assertEquals(CLOCK,f.current);assertFalse(f.pending);}
    @Test public void forceStopThenOpeningHomeRecoversEvenWithoutAlarm(){Fake f=new Fake();session(f).start();f.started=null;f.armed=false;session(f).restore();assertEquals(CLOCK,f.current);assertFalse(f.pending);}
    @Test public void respectsUserSelectingAnotherScreensaver(){Fake f=new Fake();session(f).start();f.current="other";session(f).restore();assertEquals("other",f.current);assertFalse(f.pending);}
    @Test public void failedRestoreKeepsRecoveryStateForRetry(){Fake f=new Fake();session(f).start();f.failRestore=true;try{session(f).restore();fail();}catch(IllegalStateException expected){}assertTrue(f.pending);assertTrue(f.armed);f.failRestore=false;session(f).restore();assertEquals(CLOCK,f.current);assertFalse(f.pending);}
    @Test public void restoresUnsetSelectionAndIsIdempotent(){Fake f=new Fake();f.current=null;session(f).start();session(f).restore();session(f).restore();assertNull(f.current);assertFalse(f.pending);}
}
