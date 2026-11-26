package ru.chanti.slushaet;

import java.util.ArrayList;
import java.util.Collection;

/** Pure queue rules: stable ordering, no duplicate session jobs, and safe IDs only. */
final class MagirQueueRules {
    private MagirQueueRules(){}
    static boolean validSessionName(String name){
        return name!=null&&name.matches("Magir_[a-zA-Z0-9_-]{6,80}");
    }
    static ArrayList<String> sanitize(Collection<String> values){
        ArrayList<String> result=new ArrayList<>();
        if(values==null)return result;
        for(String value:values){
            if(validSessionName(value)&&!result.contains(value))result.add(value);
        }
        return result;
    }
    static ArrayList<String> enqueue(Collection<String> current,String name){
        ArrayList<String> result=sanitize(current);
        if(validSessionName(name)&&!result.contains(name))result.add(name);
        return result;
    }
    static boolean contains(Collection<String> queue,String name){
        return validSessionName(name)&&queue!=null&&queue.contains(name);
    }
    static String first(Collection<String> queue){
        return queue==null||queue.isEmpty()?null:queue.iterator().next();
    }
    static ArrayList<String> withoutFirst(Collection<String> queue){
        ArrayList<String> result=sanitize(queue);
        if(!result.isEmpty())result.remove(0);
        return result;
    }
}
