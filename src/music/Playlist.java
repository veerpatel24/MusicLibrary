package music;

import edu.rutgers.cs112.Comparable112;
import edu.rutgers.cs112.node.LLNode;

/**
 * This class represents a playlist containing many songs.
 * 
 * The Playlist is a Circular Linked list of LLNode<Song> objects.
 */
public class Playlist extends Comparable112<Playlist>{
    private LLNode<Song> last; // reference to the last node in the Circular Linked List
    private int      size; // the number of SongNodes (songs) in the list

    /*
     * Constructor
     */
    public Playlist(LLNode<Song> last, int size) {
        this.last = last;
        this.size = size;
    }

    /*
     * Default constructor initializes the size to 0
     */
    public Playlist() {
        this(null, 0);
    }

    /* Getter and setter methods */
    public LLNode<Song> getLast() {return last;}
    public void setLast(LLNode<Song> last) {this.last = last;}

    public int getSize() {return size;}
    public void setSize(int size) {this.size = size;}

    @Override
    public int compareTo(Playlist arg0) {
        if (getSize() < arg0.getSize()) {
            return -1;
        } else if (getSize() > arg0.getSize()) {
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    public boolean equals(Object arg0) {
        if (this == arg0)
            return true;
        if (arg0 == null)
            return false;
        if (getClass() != arg0.getClass())
            return false;
        Playlist other = (Playlist) arg0;
        if (getSize() != other.getSize())
            return false;

        int n = this.getSize();
        if(n == 0){
            return true;
        }
        LLNode<Song> thisPtr = this.getLast().getNext();
        LLNode<Song> otherPtr = other.getLast().getNext();
        for(int i = 0; i < n; i++){
            if(!thisPtr.getData().equals(otherPtr.getData())){
                return false;
            }
            thisPtr = thisPtr.getNext();
            otherPtr = otherPtr.getNext();
        }
        return true;    
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (last == null) {
            sb.append("Playlist is empty.");
            return sb.toString();
        }
        LLNode<Song> ptr = last.getNext();
        int index = 1;
        do {
            sb.append(index + ". " + ptr.getData().getSongName() + " by " + ptr.getData().getArtist() +
                      " (" + ptr.getData().getYear() + ") - Popularity: " + ptr.getData().getPopularity() + "\n");
            ptr = ptr.getNext();
            index++;
        } while (ptr != last.getNext());
        return sb.toString();
    }
}