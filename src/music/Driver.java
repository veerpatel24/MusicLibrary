package music;
import java.util.*;


/**
 * This class is designed to run student implemented methods from the 
 * Library class in the console.
 * 
 * @author Jeremy Hui
 * @author Vian Miranda
 */

public class Driver {
    /**
     * NOTE TO STUDENTS: This is the seed which the Driver uses when shuffling 
     * your playlist. Feel free to change the seed to your choice, but remember 
     * that changing the seed will result in different outputs.
     */
    private final static int SEED = 2026;

    private static MusicLibrary songLibrary;

    public static void main(String[] args) {
        songLibrary = new MusicLibrary();

        String[] methods = {"addPlaylist", "removePlaylist", "loadAllPlaylists", "addSong", "findSong", "deleteSong", 
            "reversePlaylist", "combinePlaylists", "shufflePlaylist", "printLibrary"};
        String[] options = {"Test another method", "Quit"};
		int controlChoice = 1;

        do {
            StdOut.println("\nWhat method would you like to test?");
            for (int ii = 0; ii < methods.length; ii++) 
                StdOut.printf("%d. %s\n", ii + 1, methods[ii]);

            StdOut.print("Enter a number => ");
            int choice = Integer.parseInt(StdIn.readLine());

            switch (choice) {
                case 1:
                    testAddPlaylist();
                    break;
                case 2:
                    testRemovePlaylist();
                    break;            
                case 3:
                    testLoadAllPlaylists();
                    break;
                case 4:
                    testAddSong();
                    break;
                case 5:
                    testFindSong();
                    break;
                case 6:
                    testDeleteSong();
                    break;
                case 7:
                    testReversePlaylist();
                    break;
                case 8:
                    testCombinePlaylists();
                    break;
                case 9:
                    testShufflePlaylist();
                    break;
                case 10:
                    printLibrary();
                    break;
                default:
                    StdOut.println("Not a valid option!");
            }

            StdIn.resetFile();
            StdOut.println("\nWhat would you like to do now?");

            for (int ii = 0; ii < 2; ii++) 
                StdOut.printf("%d. %s\n", ii + 1, options[ii]);

            StdOut.print("Enter a number => ");
            controlChoice = Integer.parseInt(StdIn.readLine());
        } while (controlChoice == 1);
    }

    public static void testAddPlaylist() {
        StdOut.print("Enter a playlist input file to test => ");
        String fname = StdIn.readString();
        
        StdOut.print("Enter the index you want to enter the playlist at => ");
        int playlistIndex = StdIn.readInt();
        
        songLibrary.addPlaylist(fname, playlistIndex);
        songLibrary.printLibrary();
    }

    public static void testRemovePlaylist() {
        StdOut.print("Enter a playlist index to remove => ");
        int playlistIndex = StdIn.readInt();
        
        boolean res = songLibrary.removePlaylist(playlistIndex);

        if (res) StdOut.println("\nPlaylist successfully removed!");
        else StdOut.println("\nNo playlist removed (playlist not found).");

        songLibrary.printLibrary();
    }

    public static void testLoadAllPlaylists() {
        String fname;
        ArrayList<String> filenames = new ArrayList<>();
        do {
            StdOut.print("Enter a playlist input file, or type \"done\" to test => ");
            fname = StdIn.readString();
            if (!fname.equals("done")) filenames.add(fname);
        } while (!fname.equals("done"));

        int fnSize = filenames.size();
        String[] fn = new String[fnSize];
        for (int ii = 0; ii < fnSize; ii++)
            fn[ii] = filenames.get(ii);
        
        songLibrary.addAllPlaylists(fn);
        songLibrary.printLibrary();
    }

    public static void testAddSong() {
        StdOut.print("Enter a playlist index to insert a song to => ");
        int playlistIndex = StdIn.readInt();

        StdOut.print("Enter the position you want to insert the song at => ");
        int position = StdIn.readInt();

        Scanner scanner = new Scanner(System.in);
        
        StdOut.print("Enter the song name => ");
        String songName = scanner.nextLine();

        StdOut.print("Enter the artist name => ");
        String artistName = scanner.nextLine();

        StdOut.print("Enter the release year => ");
        int year = StdIn.readInt();

        StdOut.print("Enter the popularity index (0-99) => ");
        int pop = StdIn.readInt();

        Song song = new Song(songName, artistName, year, pop);

        StdOut.print("Do you have a music link that you would like to use (Y/N)? => ");
        char wavCheck = scanner.nextLine().charAt(0);

        if (wavCheck == 'y' || wavCheck == 'Y') {
            StdOut.print("Enter the name of the link => ");
            String link = scanner.nextLine();

            song.setLink(link);
        }

        boolean res = songLibrary.addSong(playlistIndex, position, song);
        if (res) StdOut.println("\nSong successfully inserted!");
        else StdOut.println("\nNo new song inserted (index invalid).");
        
        if (res) StdOut.print("New Playlist: ");
        else StdOut.print("Playlist Without Changes: ");
        songLibrary.printPlaylist(playlistIndex);
    }

    public static void testFindSong(){
        StdOut.print("Enter a playlist index to find a song in => ");
        int playlistIndex = StdIn.readInt();

        Scanner scanner = new Scanner(System.in);
        
        StdOut.print("Enter the song name to search for => ");
        String songName = scanner.nextLine();

        Song searchSong = songLibrary.findSong(playlistIndex, songName);
        if(searchSong == null){
            StdOut.println("\n" + songName + " was not found");
            StdOut.println();
        }
        else{
            StdOut.println("\nSong successfully found!");
            StdOut.println(searchSong.toString());
            StdOut.println();
        }
    }

    public static void testDeleteSong() {
        StdOut.print("Enter a playlist index to remove a song from => ");
        int playlistIndex = StdIn.readInt();

        Scanner scanner = new Scanner(System.in);
        
        StdOut.print("Enter the song name => ");
        String songName = scanner.nextLine();

        StdOut.print("Enter the artist name => ");
        String artistName = scanner.nextLine();

        StdOut.print("Enter the release year => ");
        int year = StdIn.readInt();

        StdOut.print("Enter the popularity index (0-99) => ");
        int pop = StdIn.readInt();

        Song song = new Song(songName, artistName, year, pop);

        boolean res = songLibrary.deleteSong(playlistIndex, song);
        if (res) StdOut.println("\nSong successfully removed!");
        else StdOut.println("\nNo song removed (song not found).");
        
        if (res) StdOut.print("New Playlist: ");
        else StdOut.print("Playlist Without Changes: ");
        songLibrary.printPlaylist(playlistIndex);
    }

    public static void testReversePlaylist() {
        StdOut.print("Enter an index of a playlist to reverse => ");
        int index = StdIn.readInt();

        songLibrary.reversePlaylist(index);
        
        StdOut.print("\nNew Playlist: ");
        songLibrary.printPlaylist(index);
    }

    public static void testCombinePlaylists() {
        StdOut.print("Enter the index of the first playlist to merge => ");
        int index1 = StdIn.readInt();
        StdOut.print("Enter the index of the second playlist to merge => ");
        int index2 = StdIn.readInt();

        songLibrary.combinePlaylists(index1, index2);
        songLibrary.printLibrary();
    }

    public static void testShufflePlaylist() {
        StdRandom.setSeed(SEED);
        StdOut.print("Enter the index of the playlist to shuffle => ");
        int playlistIndex = StdIn.readInt();

        songLibrary.shufflePlaylist(playlistIndex);
        StdOut.print("\nNew Playlist: ");
        songLibrary.printPlaylist(playlistIndex);
    }

    public static void printLibrary() {
        if (songLibrary.getPlaylists() == null)
            StdOut.println("\nYour library is empty!");
        else
            songLibrary.printLibrary();
    }
}
