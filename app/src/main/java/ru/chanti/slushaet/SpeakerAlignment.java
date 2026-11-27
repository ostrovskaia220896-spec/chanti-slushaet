package ru.chanti.slushaet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure timestamp alignment. Speaker ids must already be session-wide, not per part. */
final class SpeakerAlignment {
    private SpeakerAlignment(){}
    static final String UNKNOWN="unknown", MULTIPLE="multiple";
    static final class Turn {
        final long startMs,endMs;
        final String speaker;
        Turn(long start,long end,String id){
            if(start<0||end<=start||id==null||id.trim().isEmpty()
                    ||UNKNOWN.equals(id)||MULTIPLE.equals(id))
                throw new IllegalArgumentException("Invalid speaker interval");
            startMs=start;endMs=end;speaker=id;
        }
    }
    /**
     * Conservative preliminary thresholds, not calibrated confidence.
     * A second material speaker means the text cannot safely be assigned
     * as a whole. Never infer words for a particular speaker.
     */
    static String assign(long start,long end,List<Turn> turns){
        if(start<0||end<=start||turns==null)return UNKNOWN;
        Map<String,List<long[]>> bySpeaker=new HashMap<>();
        for(Turn t:turns){
            long a=Math.max(start,t.startMs), b=Math.min(end,t.endMs);
            if(b>a)bySpeaker.computeIfAbsent(t.speaker,k->new ArrayList<>())
                .add(new long[]{a,b});
        }
        double duration=(double)(end-start), best=0, second=0;
        String winner=UNKNOWN;
        for(Map.Entry<String,List<long[]>> e:bySpeaker.entrySet()){
            List<long[]> intervals=e.getValue();
            intervals.sort(Comparator.comparingLong(a->a[0]));
            long total=0, a=intervals.get(0)[0], b=intervals.get(0)[1];
            for(int i=1;i<intervals.size();i++){
                long[] x=intervals.get(i);
                if(x[0]<=b)b=Math.max(b,x[1]);
                else{total+=b-a;a=x[0];b=x[1];}
            }
            double share=(total+b-a)/duration;
            if(share>best){second=best;best=share;winner=e.getKey();}
            else if(share>second)second=share;
        }
        if(second>=0.20)return MULTIPLE;
        if(best<0.65)return UNKNOWN;
        return winner;
    }
}
