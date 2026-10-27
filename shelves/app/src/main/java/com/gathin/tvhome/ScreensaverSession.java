package com.gathin.tvhome;

/** Persisted transaction; a new process can recover without an alarm or startup broadcast. */
final class ScreensaverSession {
    interface Host {
        boolean pending(); String original(); String current();
        void save(String original); void select(String component); void armRecovery();
        void watchStart(Runnable started); void launch(); void clear();
    }
    private final Host host;
    private final String temporary;
    ScreensaverSession(Host host,String temporary){this.host=host;this.temporary=temporary;}
    void start(){
        if(host.pending())restore();
        host.save(host.current());
        try{
            host.armRecovery(); host.watchStart(this::restore);
            host.select(temporary); host.launch();
        }catch(RuntimeException e){
            try{restore();}catch(RuntimeException recovery){e.addSuppressed(recovery);}
            throw e;
        }
    }
    void restore(){
        if(!host.pending())return;
        if(temporary.equals(host.current()))host.select(host.original());
        host.clear();
    }
}
