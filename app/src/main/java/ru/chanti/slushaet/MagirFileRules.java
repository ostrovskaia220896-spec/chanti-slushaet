package ru.chanti.slushaet;

/** Pure filename policy shared by import, recovery and session tests. */
final class MagirFileRules {
    private MagirFileRules(){}
    static boolean isCompleteAudio(String name){
        return name!=null&&name.matches("part_\\d{4}\\.m4a");
    }
    static boolean isPendingAudio(String name){
        return name!=null&&name.matches("part_\\d{4}\\.m4a\\.pending");
    }
    static boolean isPartTranscript(String name){
        return name!=null&&name.matches("part_\\d{4}\\.whisper\\.txt");
    }
    static boolean isSafeImportEntry(String name){
        return isCompleteAudio(name)||isPendingAudio(name)||isPartTranscript(name)
            ||"transcript_whisper_ru.txt".equals(name)
            ||"transcript_imported_ru.txt".equals(name);
    }
    static String transcriptForAudio(String name){
        if(!isCompleteAudio(name))throw new IllegalArgumentException("Not a completed Magir audio part");
        return name.substring(0,name.length()-4)+".whisper.txt";
    }
}
