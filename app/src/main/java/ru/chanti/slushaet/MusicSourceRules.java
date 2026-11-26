package ru.chanti.slushaet;

/** Resolves disagreeing media sources without bouncing back to a known stale title. */
final class MusicSourceRules {
    private String sessionKey="",notificationKey="";
    private boolean notificationSelected=false;
    void reset(){sessionKey="";notificationKey="";notificationSelected=false;}
    private static String key(String title,String artist){
        return title.isEmpty()?"":title+"\u001f"+artist;
    }
    boolean useNotification(String st,String sa,String nt,String na){
        String session=key(st,sa),notification=key(nt,na);
        boolean sessionChanged=!session.equals(sessionKey);
        boolean notificationChanged=!notification.equals(notificationKey);
        sessionKey=session;notificationKey=notification;
        if(nt.isEmpty()){notificationSelected=false;return false;}
        if(st.isEmpty()){notificationSelected=true;return true;}
        if(st.equals(nt)&&(sa.isEmpty()||na.isEmpty()||sa.equals(na))){
            notificationSelected=sa.isEmpty()&&!na.isEmpty();
            return notificationSelected;
        }
        // When only one source advances, keep it until the other really changes.
        if(notificationChanged&&!sessionChanged)notificationSelected=true;
        else if(sessionChanged&&!notificationChanged)notificationSelected=false;
        else if(sessionChanged&&notificationChanged)notificationSelected=true;
        return notificationSelected;
    }
}
