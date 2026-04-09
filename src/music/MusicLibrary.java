package music;

import java.util.ArrayList;
import edu.rutgers.cs112.node.LLNode;

/**
 * This class represents a library of song playlists.
 *
 * An ArrayList of Playlist objects represents the various playlists 
 * within one's library.
 * 
 * @author Jeremy Hui
 * @author Vian Miranda
 */
public class MusicLibrary {

    private ArrayList<Playlist> allPlaylists; // contains various playlists

    /**
     * DO NOT EDIT!
     * Constructor for Library.
     * 
     * @param allPlaylists passes in ArrayList of playlists
     */
    public MusicLibrary(ArrayList<Playlist> allPlaylists) {
        this.allPlaylists = allPlaylists;
    }

    /**
     * DO NOT EDIT!
     * Default constructor for an empty library. 
     */
    public MusicLibrary() {
        this(null);
    }

    /**
     * This method reads the songs from an input csv file, and creates a 
     * playlist from it.
     * Add new songs to the end of the circular linked lists
     * Read the instructions on the website for more detail
     * 
     * @param filename the playlist information input file
     * @return a Playlist object, which contains a reference to the LAST song 
     * in the ciruclar linkedlist playlist and the size of the playlist.
     */
    public Playlist constructPlaylist(String filename) {
        // write code here
        Playlist playlist = new Playlist();
        StdIn.setFile(filename);
        LLNode<Song> end=null;
        int count=0;
        while(!StdIn.isEmpty()){
            String[] data = StdIn.readLine().split(",");
            String name = data[0];
            String artist = data[1];
            int year = Integer.parseInt(data[2]);
            int popularity = Integer.parseInt(data[3]);
            String link = data[4];
            Song song = new Song(name, artist, year, popularity, link);
            LLNode<Song> newNode = new LLNode<>(song);
            if (end == null){
                end = newNode;
                end.setNext(end);
            }
            else{
                newNode.setNext(end.getNext());
                end.setNext(newNode);
                end = newNode;
            }
            count++;
        }
        playlist.setLast(end);
        playlist.setSize(count);
        return playlist;
    }

    /**
     * ****DO NOT**** UPDATE THIS METHOD
     * This method is already implemented for you. 
     * 
     * Adds a new playlist into the song library at a certain index.
     * 
     * @param filename the playlist information input file
     * @param playlistIndex the index of the location where the playlist will 
     * be added 
     */
    public void addPlaylist(String filename, int playlistIndex) {
        
        /* DO NOT UPDATE THIS METHOD */

        if ( allPlaylists == null ) {
            allPlaylists = new ArrayList<Playlist>();
        }
        if ( playlistIndex >= allPlaylists.size() ) {
            allPlaylists.add(constructPlaylist(filename));
        } 
        else if(playlistIndex < 0){
            allPlaylists.add(0, constructPlaylist(filename));
        }
        else {
            allPlaylists.add(playlistIndex, constructPlaylist(filename));
        }        
    }

    /**
     * ****DO NOT**** UPDATE THIS METHOD
     * This method is already implemented for you.
     * 
     * It takes a playlistIndex, and removes the playlist located at that index.
     * 
     * @param playlistIndex the index of the playlist to remove
     * @return true if the playlist has been deleted
     */
    public boolean removePlaylist(int playlistIndex) {
        /* DO NOT UPDATE THIS METHOD */

        if ( allPlaylists == null || playlistIndex >= allPlaylists.size() || playlistIndex < 0) {
            return false;
        }

        allPlaylists.remove(playlistIndex);
            
        return true;
    }
    
    /** 
     * ****DO NOT**** UPDATE THIS METHOD
     * This method is already implemented for you.
     * Adds multiple playlist to different indices based on the provided filenames
     * 
     * @param filenames an array of the filenames of playlists that should be 
     * added to the library
     */
    public void addAllPlaylists(String[] filenames) {
        
        // do not update this method
        allPlaylists = new ArrayList<Playlist>();
        
        for ( int ii = 0; ii < filenames.length; ii++ ) {
            addPlaylist(filenames[ii], ii);
        }
    }

    /**
     * This method adds a song to a specified playlist at a given position.
     * 
     * See the assignment description for full details 
     * 
     * @param playlistIndex the index where the playlist will be added
     * @param position the position in the playlist to which the song 
     * is to be added 
     * @param newSong the song to add
     * @return true if the song can be added and therefore has been added, 
     * false otherwise. 
     */
    public boolean addSong(int playlistIndex, int position, Song newSong) {
        // write code here
        Playlist playlist = allPlaylists.get(playlistIndex);
        int size = playlist.getSize();
        if(position<1 || position>size+1){
            return false;
        }
        LLNode<Song> newNode = new LLNode<>(newSong);
        if (size==0){
            newNode.setNext(newNode);
            playlist.setLast(newNode);
        }
        else if(position==1){
            newNode.setNext(playlist.getLast().getNext());
            playlist.getLast().setNext(newNode);
        }
        else if (position==size+1){
            newNode.setNext(playlist.getLast().getNext());
            playlist.getLast().setNext(newNode);
            playlist.setLast(newNode);
        }
        else{
            LLNode<Song> previous = playlist.getLast().getNext();
            for(int i=1; i<position-1; i++){
                previous=previous.getNext();
            }
            newNode.setNext(previous.getNext());
            previous.setNext(newNode);
        }
        playlist.setSize(size+1);
        return true;
    
    }

    /**
     * Find a song in a given playlist given its name
     * @param playlistIndex
     * @param songName
     * @return Song object of song if found, otherwise null
     */
    public Song findSong(int playlistIndex, String songName){
        // write code here
        Playlist playlist = allPlaylists.get(playlistIndex);
        LLNode<Song> end = playlist.getLast();
        if(end==null){
            return null;
        }
        LLNode<Song> playing=end.getNext();
        do {
            if(playing.getData().getSongName().equals(songName)){
                return playing.getData();
            }
            playing = playing.getNext();
        } while(playing!=end.getNext());
        return null;
    
    }

    /**
     * This method removes a song at a specified playlist, if the song exists. 
     *
     * See the assignment description for full details 
     * 
     * @param playlistIndex the playlist index within the songLibrary where 
     * the song is to be added.
     * @param song the song to remove.
     * @return true if the song is present in the playlist and therefore has 
     * been removed, false otherwise.
     */
    public boolean deleteSong(int playlistIndex, Song song) {
        // write code here
        Playlist playlist = allPlaylists.get(playlistIndex);
        LLNode<Song> end = playlist.getLast();
        if(end==null){
            return false;
        }
        if(playlist.getSize()==1){
            if(end.getData().equals(song)){
                playlist.setLast(null);
                playlist.setSize(0);
                return true;
            }
            else{return false;}
        }
        LLNode<Song> previous = end;
        LLNode<Song> playing = end.getNext();
        do {
            if(playing.getData().equals(song)){
                previous.setNext(playing.getNext());
                if(playing==end){
                    playlist.setLast(previous);
                }
                playlist.setSize(playlist.getSize()-1);
                return true;
            }
            previous = playing;
            playing = playing.getNext();
        } while(playing!=end.getNext());
        return false;
    
    }

    /**
     * This method reverses the playlist located at playlistIndex
     * 
     * Each node in the circular linked list will point to the element that 
     * came before it.
     * 
     * @param playlistIndex the playlist to reverse
     */
    public void reversePlaylist(int playlistIndex) {
        // write code here
        Playlist playlist = allPlaylists.get(playlistIndex);
        LLNode<Song> end = playlist.getLast();
        if(end==null||playlist.getSize()==1){
            return;
        }
        LLNode<Song> front = end.getNext();
        LLNode<Song> previous = end;
        LLNode<Song> playing = front;
        LLNode<Song> next;
        do {
            next = playing.getNext();
            playing.setNext(previous);
            previous = playing;
            playing = next;
        } while(playing!=front);
        playlist.setLast(front);
    
    }

    /**
     * This method combines two playlists.
     * 
     * See the assignment description on the website for full details on 
     * the procedure for combining the 2 playlists 
     * 
     * @param playlistIndex1 the first playlist to merge into one playlist
     * @param playlistIndex2 the second playlist to merge into one playlist
     */
    public void combinePlaylists(int playlistIndex1, int playlistIndex2) {

    }

    /**
     * This method shuffles a specified playlist
     * 
     * See the full procedure for shuffling on the assignment description on the website
     *    
     * @param playlistIndex the playlist to shuffle in songLibrary
     */
    public void shufflePlaylist(int playlistIndex) {

    }
    /**
     * ****DO NOT**** UPDATE THIS METHOD
     * Prints playlist by index; can use this method to debug.
     * 
     * @param playlistIndex the playlist to print
     */
    public void printPlaylist(int playlistIndex) {
        StdOut.printf("%nPlaylist at index %d (%d song(s)):%n", playlistIndex, allPlaylists.get(playlistIndex).getSize());
        if (allPlaylists.get(playlistIndex).getLast() == null) {
            StdOut.println("EMPTY");
            return;
        }
        LLNode<Song> ptr;
        for (ptr = allPlaylists.get(playlistIndex).getLast().getNext(); ptr != allPlaylists.get(playlistIndex).getLast(); ptr = ptr.getNext() ) {
            StdOut.print(ptr.getData().toString() + " -> ");
        }
        if (ptr == allPlaylists.get(playlistIndex).getLast()) {
            StdOut.print(allPlaylists.get(playlistIndex).getLast().getData().toString() + " -> POINTS TO FRONT");
        }
        StdOut.println();
    }

    public void printLibrary() {
        if (allPlaylists.size() == 0) {
            StdOut.println("\nYour library is empty!");
        } else {
                for (int ii = 0; ii < allPlaylists.size(); ii++) {
                printPlaylist(ii);
            }
        }
    }

    /*
     * Used to get and set objects.
     * DO NOT edit.
     */
     public ArrayList<Playlist> getPlaylists() { return allPlaylists; }
     public void setPlaylists(ArrayList<Playlist> p) { allPlaylists = p; }
}
