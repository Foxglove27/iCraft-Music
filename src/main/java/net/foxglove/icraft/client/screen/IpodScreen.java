package net.foxglove.icraft.client.screen;

import com.mojang.math.Axis;
import net.foxglove.icraft.client.IpodMute;
import net.foxglove.icraft.client.DockControl;
import net.foxglove.icraft.client.IpodPlayer;
import net.foxglove.icraft.client.IpodSettings;
import net.foxglove.icraft.client.IpodVolume;
import net.foxglove.icraft.client.audio.AudiobookProgress;
import net.foxglove.icraft.client.audio.CoverArt;
import net.foxglove.icraft.client.audio.LibraryCache;
import net.foxglove.icraft.client.audio.LocalMusic;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSong;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class IpodScreen extends Screen {
    // ---- The texture ----
    private static final int TEX_SIZE = 1280; // the file is 1280 x 1280 pixels

    // Where the iPod is inside that file (x, y, width, height in file pixels)
    private static final int SRC_X = 288;
    private static final int SRC_Y = 144;
    private static final int SRC_W = 704;
    private static final int SRC_H = 1024;

    // ---- Sizes ----
    // How many GUI pixels one pixel of the artwork is drawn as. Change this to resize the iPod.
    private static final int PX = 4;

    private static final int BODY_W = 44 * PX;
    private static final int BODY_H = 64 * PX;

    // The display, measured from the iPod's top-left corner
    private static final int DISPLAY_X = 6 * PX;
    private static final int DISPLAY_Y = 6 * PX;
    private static final int DISPLAY_W = 32 * PX;
    private static final int DISPLAY_H = 23 * PX;
    private static final int HEADER_H = 5 * PX; // the light blue strip at the top of the display
    private static final int STATUS_ICON_Y = DISPLAY_Y + (HEADER_H - 5) / 2; // top of the play / pause symbol in the header

    // The click wheel
    private static final int WHEEL_X = 22 * PX; // centre of the wheel
    private static final int WHEEL_Y = 46 * PX;
    private static final int WHEEL_R = 13 * PX; // outer radius
    private static final int CENTER_R = 5 * PX; // radius of the middle button

    // The list inside the display
    private static final int LIST_Y = DISPLAY_Y + HEADER_H;
    private static final int ROW_H = 12;
    private static final int ROWS = 5;
    private static final int PAD = 3;
    private static final int THUMB = 10; // size of the little album cover

    // The open/close animation lasts this many milliseconds
    private static final long ANIM_MS = 280;

    // Two clicks on the middle button closer together than this count as a double-click
    private static final long DOUBLE_CLICK_MS = 350;

    // When a single click on the middle button is waiting to see if a second one follows,
    // this is the time (in milliseconds) when we give up waiting. -1 means nothing is waiting.
    private long pendingActivateAt = -1;

    // 'detail' is a small grey text after the name (for example the saved time in an audiobook chapter)
    // 'item' describes the file when the track can be shared through a dock (file sharing)
    private record Track(Component name, Runnable play, @Nullable String detail, @Nullable DockControl.FileItem item) {
        Track(Component name, Runnable play) {
            this(name, play, null, null);
        }
    }

    // An album or a book. 'startRow' is the row to highlight when it is opened (a book's last chapter).
    // 'detail' is a small grey text on the right of the row (the disc count "12/32" on the Music Discs row)
    private record AlbumView(Component name, @Nullable Path cover, @Nullable ItemStack icon, List<Track> tracks,
                             int startRow, @Nullable String detail) {
        AlbumView(Component name, @Nullable Path cover, @Nullable ItemStack icon, List<Track> tracks) {
            this(name, cover, icon, tracks, 0, null);
        }

        AlbumView(Component name, @Nullable Path cover, @Nullable ItemStack icon, List<Track> tracks, int startRow) {
            this(name, cover, icon, tracks, startRow, null);
        }
    }

    // Which list the display is showing. Menu goes up one level: TRACKS -> ALBUMS -> MENU,
    // CHAPTERS -> BOOKS -> MENU, SETTINGS -> MENU.
    private enum Page { MENU, ALBUMS, TRACKS, BOOKS, CHAPTERS, SETTINGS, SEARCH, CONFIRM_RESET }

    // ids of the menu entries (the rows are Music, NAS (only with extra folders), Audiobooks, Podcasts, Settings)
    private static final int MENU_MUSIC = 0;
    private static final int MENU_BOOKS = 1;
    private static final int MENU_PODCASTS = 2;
    private static final int MENU_SETTINGS = 3;
    private static final int MENU_NAS = 4;
    // the entries of the NAS submenu
    private static final int MENU_SEARCH = 8;
    private static final int MENU_MANUAL = 9; // opens the iCraft Manual
    private static final int NAS_MUSIC = 5;
    private static final int NAS_BOOKS = 6;
    private static final int NAS_PODCASTS = 7;
    private static final int SETTING_SHUFFLE = 0;
    private static final int SETTING_REPEAT = 1;
    private static final int SETTING_BASS = 2;
    private static final int SETTING_WORLD = 3;
    private static final int SETTING_STEREO = 4;
    private static final int SETTING_GAME_MUSIC = 5;
    private static final int SETTING_DOWNLOADS = 6;
    private static final int SETTING_DOCK_DISPLAY = 7;
    private static final int SETTING_RESET = 8; // opens the confirmation page
    private static final int SETTING_ROWS = 9;
    // the confirmation page for the reset: Cancel first, so a hasty click changes nothing
    private static final int CONFIRM_CANCEL = 0;
    private static final int CONFIRM_RESET_ROW = 1;

    private Page page = Page.MENU;

    @Nullable
    private final GlobalPos dock; // the dock this iPod sits in, or null when the player holds it
    private final boolean dockOwner; // only the player who docked the iPod controls the dock
    private final ResourceLocation skin; // the GUI texture for this iPod's colour
    private final List<AlbumView> albums = new ArrayList<>();
    private final List<AlbumView> nas = new ArrayList<>();

    // The ALBUMS and TRACKS pages show either the local music or the NAS music
    private enum AlbumSection { MUSIC, NAS }
    private AlbumSection albumSection = AlbumSection.MUSIC;
    // ---- search: an index of everything the lists show, filtered by the typed text ----
    // 'row' is -1 for the album/book/show itself, otherwise the track/chapter/episode row inside 'view'.
    private record SearchEntry(AlbumView view, int row, boolean chapters, String label, String text, String compact,
                               String tag) {}
    private static final int SEARCH_MAX_RESULTS = 200;
    private static final int SEARCH_MAX_LENGTH = 32;
    private final List<SearchEntry> searchIndex = new ArrayList<>();
    private List<SearchEntry> results = List.of();
    private String searchQuery = "";
    private boolean cameFromSearch; // the album page was opened from a search result: Menu goes back to the results
    private int searchSelected;

    private final boolean nasEnabled = LocalMusic.hasNasFolders();
    private boolean inNas; // the MENU page shows the NAS submenu (Music, Audiobooks, Podcasts from the extra folders)

    private List<AlbumView> shownAlbums() {
        return albumSection == AlbumSection.NAS ? nas : albums;
    }

    // the menu entries in the order they are shown
    private List<Integer> menuIds() {
        if (inNas) {
            List<Integer> ids = new ArrayList<>();
            if (LocalMusic.hasNasFolders(LocalMusic.Section.MUSIC)) ids.add(NAS_MUSIC);
            if (LocalMusic.hasNasFolders(LocalMusic.Section.AUDIOBOOKS)) ids.add(NAS_BOOKS);
            if (LocalMusic.hasNasFolders(LocalMusic.Section.PODCASTS)) ids.add(NAS_PODCASTS);
            return ids;
        }
        return nasEnabled ? List.of(MENU_MUSIC, MENU_NAS, MENU_BOOKS, MENU_PODCASTS, MENU_SEARCH, MENU_SETTINGS, MENU_MANUAL)
                : List.of(MENU_MUSIC, MENU_BOOKS, MENU_PODCASTS, MENU_SEARCH, MENU_SETTINGS, MENU_MANUAL);
    }

    private int menuRowOf(int id) {
        return Math.max(0, menuIds().indexOf(id));
    }
    private final List<AlbumView> books = new ArrayList<>();
    private final List<AlbumView> podcasts = new ArrayList<>();
    private final List<AlbumView> nasBooks = new ArrayList<>();
    private final List<AlbumView> nasPodcasts = new ArrayList<>();

    // The BOOKS and CHAPTERS pages show either the audiobooks or the podcasts
    private enum BookSection { AUDIOBOOKS, PODCASTS, NAS_AUDIOBOOKS, NAS_PODCASTS }
    private BookSection bookSection = BookSection.AUDIOBOOKS;

    private List<AlbumView> shownBooks() {
        return switch (bookSection) {
            case PODCASTS -> podcasts;
            case NAS_AUDIOBOOKS -> nasBooks;
            case NAS_PODCASTS -> nasPodcasts;
            case AUDIOBOOKS -> books;
        };
    }

    private boolean isPodcastSection() {
        return bookSection == BookSection.PODCASTS || bookSection == BookSection.NAS_PODCASTS;
    }

    private boolean isNasBookSection() {
        return bookSection == BookSection.NAS_AUDIOBOOKS || bookSection == BookSection.NAS_PODCASTS;
    }
    @Nullable
    private AlbumView open;   // the album shown on the TRACKS page, or the book on the CHAPTERS page
    private int openedIndex;  // which album we opened, so Menu can jump back to it
    private int selected;     // the highlighted row in the current list
    private int scroll;       // index of the first row that is visible

    // The album whose list was last started from this screen, with the list handed to the player.
    // Lets the highlight follow the song when it changes (Next, Previous, autoplay, shuffle).
    @Nullable
    private AlbumView playedAlbum;
    @Nullable
    private List<Runnable> playedActions;
    private int lastPlayingSeen = -1;

    // Holding the left or right side of the wheel skips within the song; a quick click changes track
    private enum Hold { NONE, PREVIOUS, NEXT }
    private static final long HOLD_START_MS = 400;  // how long before skipping begins
    private static final long HOLD_STEP_MS = 400;   // time between skips while held
    private static final double HOLD_SKIP_SECONDS = 10;
    private Hold hold = Hold.NONE;
    private long holdStart;
    private long holdLastStep;
    private boolean holdSkipped;

    private long animStart;
    private boolean closing;
    private float closeFrom;

    private final List<ItemStack> discs;
    @Nullable
    private final UUID ipodId;
    private LibraryCache.Snapshot shown = LibraryCache.get(); // the scan the lists were built from

    public IpodScreen(List<ItemStack> discs, @Nullable GlobalPos dock, boolean dockOwner,
                      ResourceLocation skin, @Nullable UUID ipod) {
        super(Component.translatable("screen.icraft_music.ipod"));
        this.skin = skin;
        this.dock = dock;
        this.dockOwner = dockOwner;
        this.discs = discs;
        this.ipodId = ipod;
        buildLists(shown);

        animStart = Util.getMillis();
    }

    // (Re)builds the album, book and podcast lists from a library scan
    private void buildLists(LibraryCache.Snapshot snapshot) {
        UUID ipod = ipodId;
        albums.clear();
        nas.clear();
        books.clear();
        podcasts.clear();
        nasBooks.clear();
        nasPodcasts.clear();
        if (!discs.isEmpty()) {
            List<Track> tracks = new ArrayList<>();
            for (ItemStack disc : discs) {
                tracks.add(new Track(discName(disc), () -> IpodPlayer.play(disc, ipod)));
            }
            albums.add(new AlbumView(Component.translatable("screen.icraft_music.ipod.discs"),
                    null, new ItemStack(Items.MUSIC_DISC_CAT), tracks, 0,
                    discs.size() + "/" + net.foxglove.icraft.item.IpodItem.MAX_DISCS));
        }

        for (LocalMusic.Album album : snapshot.albums()) {
            List<Track> tracks = new ArrayList<>();
            for (Path file : album.files()) {
                tracks.add(new Track(Component.literal(LocalMusic.displayName(file)),
                        () -> IpodPlayer.playFile(file, ipod),
                        null, // songs show no saved time and are not resumed
                        new DockControl.FileItem(file, Component.literal(LocalMusic.displayName(file)), album.name(),
                                album.cover(), 0, -1, null, LocalMusic.key(file), null)));
            }
            albums.add(new AlbumView(album.name(), album.cover(), null, tracks));
        }

        for (LocalMusic.Album album : snapshot.nas()) {
            List<Track> tracks = new ArrayList<>();
            for (Path file : album.files()) {
                // NAS songs can be shared at a dock like local ones (read-only; nothing is written into the share)
                tracks.add(new Track(Component.literal(LocalMusic.displayName(file)),
                        () -> IpodPlayer.playFile(file, ipod),
                        null,
                        new DockControl.FileItem(file, Component.literal(LocalMusic.displayName(file)), album.name(),
                                album.cover(), 0, -1, null, LocalMusic.key(file), null)));
            }
            nas.add(new AlbumView(album.name(), album.cover(), null, tracks));
        }

        buildBooks(snapshot.books(), books, ipod);
        buildBooks(snapshot.podcasts(), podcasts, ipod);
        buildBooks(snapshot.nasBooks(), nasBooks, ipod);
        buildBooks(snapshot.nasPodcasts(), nasPodcasts, ipod);
        buildSearchIndex();
    }

    // Lower case, no accents, only letters and digits separated by single spaces ("AVĪCI" -> "avici", "AC+DC" -> "ac dc")
    private static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return s.toLowerCase(Locale.ROOT).replace("ß", "ss").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private void buildSearchIndex() {
        searchIndex.clear();
        for (AlbumView view : albums) indexList(view, view.icon() != null ? "disc" : "album", view.icon() != null ? "disc" : "song", false);
        for (AlbumView view : nas) indexList(view, "album", "song", false);
        for (AlbumView view : books) indexList(view, "book", "chapter", true);
        for (AlbumView view : nasBooks) indexList(view, "book", "chapter", true);
        for (AlbumView view : podcasts) indexList(view, "show", "episode", true);
        for (AlbumView view : nasPodcasts) indexList(view, "show", "episode", true);
        updateResults();
    }

    private void indexList(AlbumView view, String albumTag, String trackTag, boolean chapters) {
        String album = view.name().getString();
        searchIndex.add(new SearchEntry(view, -1, chapters, album, normalize(album), normalize(album).replace(" ", ""), albumTag));
        for (int i = 0; i < view.tracks().size(); i++) {
            String track = view.tracks().get(i).name().getString();
            String label = album + " - " + track;
            String text = normalize(label);
            searchIndex.add(new SearchEntry(view, i, chapters, label, text, text.replace(" ", ""), trackTag));
        }
    }

    // Finds the entries that contain every typed word (best: every word starts a word); albums before tracks
    private void updateResults() {
        String q = normalize(searchQuery);
        if (q.isEmpty()) {
            results = List.of();
            return;
        }
        String[] words = q.split(" ");
        String compactQuery = q.replace(" ", "");
        List<SearchEntry> prefix = new ArrayList<>();
        List<SearchEntry> contains = new ArrayList<>();
        for (SearchEntry e : searchIndex) {
            String padded = " " + e.text();
            boolean allPrefix = true, allContain = true;
            for (String w : words) {
                if (!padded.contains(" " + w)) allPrefix = false;
                if (!e.text().contains(w)) allContain = false;
            }
            if (allPrefix) prefix.add(e);
            else if (allContain || e.compact().contains(compactQuery)) contains.add(e);
        }
        List<SearchEntry> all = new ArrayList<>(prefix.size() + contains.size());
        for (List<SearchEntry> group : List.of(prefix, contains)) {
            for (SearchEntry e : group) if (e.row() < 0) all.add(e);
            for (SearchEntry e : group) if (e.row() >= 0) all.add(e);
        }
        results = all.size() > SEARCH_MAX_RESULTS ? new ArrayList<>(all.subList(0, SEARCH_MAX_RESULTS)) : all;
    }

    private void searchChanged() {
        updateResults();
        selected = 0;
        scroll = 0;
    }


    // Turns books (or podcast shows) into list entries whose rows play a chapter (or episode)
    private static void buildBooks(List<LocalMusic.Book> library, List<AlbumView> into, @Nullable UUID ipod) {
        for (LocalMusic.Book book : library) {
            List<Track> chapters = new ArrayList<>();
            List<LocalMusic.Chapter> list = book.chapters();
            String last = AudiobookProgress.lastChapter(book.key());
            int startRow = 0;
            for (int i = 0; i < list.size(); i++) {
                LocalMusic.Chapter chapter = list.get(i);
                String nextKey = i + 1 < list.size() ? list.get(i + 1).key() : null;
                double saved = AudiobookProgress.get(book.key(), chapter.key());
                chapters.add(new Track(chapter.title(),
                        () -> IpodPlayer.playChapter(chapter, book.key(), nextKey, ipod),
                        saved >= 1 ? formatTime(saved) : null,
                        new DockControl.FileItem(chapter.file(), chapter.title(), book.name(), book.cover(),
                                chapter.startSeconds(), chapter.endSeconds(), book.key(), chapter.key(), nextKey)));
                if (chapter.key().equals(last)) startRow = i;
            }
            into.add(new AlbumView(book.name(), book.cover(), null, chapters, startRow));
        }
    }

    private static String formatTime(double seconds) {
        int total = (int) seconds;
        int h = total / 3600, m = (total / 60) % 60, sec = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private static Component discName(ItemStack disc) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return JukeboxSong.fromStack(mc.level.registryAccess(), disc)
                    .map(holder -> holder.value().description())
                    .orElse(disc.getHoverName());
        }
        return disc.getHoverName();
    }

    // =====================================================================
    // Animation
    // =====================================================================

    // 0 = the iPod is off the bottom of the screen, 1 = it is in place
    private float progress() {
        float t = Mth.clamp((Util.getMillis() - animStart) / (float) ANIM_MS, 0.0F, 1.0F);
        if (closing) {
            return closeFrom * (1.0F - t * t * t);   // speeds up as it leaves
        }
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;               // slows down as it arrives
    }

    // True once the iPod has arrived: only then do we accept clicks
    private boolean settled() {
        return !closing && progress() >= 1.0F;
    }

    // Shrinks the iPod if the window is too small to show it fully
    private float fitScale() {
        return Math.min(1.0F, (this.height - 16) / (float) BODY_H);
    }

    // Esc (and anything else that closes the screen) starts the closing animation instead
    @Override
    public void onClose() {
        if (closing) return;
        closeFrom = progress();
        closing = true;
        animStart = Util.getMillis();
    }

    // A newer library scan finished: rebuild the lists if something changed, keeping the open album where possible
    private void refreshLibrary() {
        LibraryCache.Snapshot latest = LibraryCache.get();
        if (latest.generation() == shown.generation()) return;
        boolean changed = !latest.sameContentAs(shown);
        shown = latest;
        if (!changed) return;

        Component openName = open != null ? open.name() : null;
        Component playedName = playedAlbum != null ? playedAlbum.name() : null;
        buildLists(latest);
        playedAlbum = playedName == null ? null : findByName(playedName);
        if (page == Page.TRACKS || page == Page.CHAPTERS) {
            AlbumView again = openName == null ? null : findByName(openName);
            if (again != null) {
                open = again;
            } else {
                open = null;
                inNas = false;
                cameFromSearch = false;
                goTo(Page.MENU, 0);
            }
        }
        selected = Mth.clamp(selected, 0, Math.max(0, listSize() - 1));
    }

    @Nullable
    private AlbumView findByName(Component name) {
        for (List<AlbumView> list : List.of(albums, nas, books, podcasts, nasBooks, nasPodcasts)) {
            for (AlbumView view : list) {
                if (view.name().getString().equals(name.getString())) return view;
            }
        }
        return null;
    }

    @Override
    public void tick() {
        refreshLibrary();
        // A single click on the middle button has waited long enough: no second click came, so do it now
        if (pendingActivateAt >= 0 && Util.getMillis() >= pendingActivateAt) {
            pendingActivateAt = -1;
            activate();
        }

        // The song changed by itself or through Next/Previous: move the highlight to it
        // the dock plays the discs: the highlight follows the disc it is on
        if (page == Page.TRACKS && dock != null && open != null && open.icon() != null && DockControl.isActive()) {
            int disc = DockControl.currentDisc();
            if (disc >= 0 && disc != lastPlayingSeen) {
                lastPlayingSeen = disc;
                selected = disc;
            }
        } else if ((page == Page.TRACKS || page == Page.CHAPTERS) && open == playedAlbum && DockControl.isFilesActive()) {
            int shown = DockControl.currentIndexInAlbum(); // the dock plays the shared list: follow it
            if (shown >= 0 && shown != lastPlayingSeen) {
                lastPlayingSeen = shown;
                selected = shown;
            }
        } else if ((page == Page.TRACKS || page == Page.CHAPTERS) && open == playedAlbum && playedActions != null
                && IpodPlayer.isQueue(playedActions)) {
            int playing = IpodPlayer.playingQueueIndex();
            if (playing >= 0 && playing != lastPlayingSeen) {
                lastPlayingSeen = playing;
                selected = playing; // keepSelectionVisible scrolls it into view when drawing
            }
        }

        // Left or right held down: skip ahead or back within the song
        if (hold != Hold.NONE && IpodPlayer.canSkipSeconds()) {
            long now = Util.getMillis();
            if (now - holdStart >= HOLD_START_MS && now - holdLastStep >= HOLD_STEP_MS) {
                holdLastStep = now;
                holdSkipped = true; // also when paused: a hold never changes track
                IpodPlayer.skipSeconds(hold == Hold.NEXT ? HOLD_SKIP_SECONDS : -HOLD_SKIP_SECONDS);
            }
        }

        if (closing && progress() <= 0.0F) {
            super.onClose(); // the animation has finished, so really close the screen
        }
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No blur, so the world stays visible. Just a light dim that fades in as the iPod arrives.
        int alpha = (int) (progress() * 90);
        g.fill(0, 0, this.width, this.height, alpha << 24);
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);

        float p = progress();
        float fit = fitScale();
        float scale = fit * (0.85F + 0.15F * p);                               // grows as it arrives
        float slide = (1.0F - p) * (this.height * 0.5F + BODY_H * 0.5F * fit); // starts below the screen

        // Everything between pushPose and popPose is drawn in "iPod coordinates":
        // (0, 0) is the iPod's top-left corner, and the whole animation is applied to it at once.
        g.pose().pushPose();
        g.pose().translate(this.width / 2.0F, this.height / 2.0F + slide, 0.0F);
        g.pose().mulPose(Axis.ZP.rotationDegrees((1.0F - p) * -8.0F));          // tilts as if picked up
        g.pose().scale(scale, scale, 1.0F);
        g.pose().translate(-BODY_W / 2.0F, -BODY_H / 2.0F, 0.0F);

        // 1) the iPod picture
        g.blit(skin, 0, 0, BODY_W, BODY_H, SRC_X, SRC_Y, SRC_W, SRC_H, TEX_SIZE, TEX_SIZE);

        // 2) the contents of the display
        drawDisplay(g);

        g.pose().popPose();
    }

    private void drawDisplay(GuiGraphics g) {
        int left = DISPLAY_X + PAD;
        int right = DISPLAY_X + DISPLAY_W - PAD;
        int count = listSize();
        keepSelectionVisible();

        // Header: dark text on the light blue strip, plus a play/pause symbol
        Component title = switch (page) {
            case MENU -> Component.translatable(inNas ? "screen.icraft_music.ipod.nas" : "screen.icraft_music.ipod.menu");
            case ALBUMS -> Component.translatable(albumSection == AlbumSection.NAS
                    ? "screen.icraft_music.ipod.nas" : "screen.icraft_music.ipod.albums");
            case BOOKS -> Component.translatable(isPodcastSection()
                    ? "screen.icraft_music.ipod.podcast_list" : "screen.icraft_music.ipod.books");
            case SETTINGS -> Component.translatable("screen.icraft_music.ipod.settings");
            case CONFIRM_RESET -> Component.translatable("screen.icraft_music.ipod.reset_title");
            case SEARCH -> Component.literal(searchQuery.isEmpty() ? Component.translatable("screen.icraft_music.ipod.search").getString()
                    : searchQuery + ((Util.getMillis() / 500) % 2 == 0 ? "_" : " "));
            case TRACKS, CHAPTERS -> {
                assert open != null;
                yield open.name();
            }
        };
        // while this iPod is muted for the player, "Muted" stands next to the status symbol
        String muted = IpodMute.silences(ipodId)
                ? Component.translatable("screen.icraft_music.ipod.muted").getString() : "";
        int titleRoom = right - left - 10 - (muted.isEmpty() ? 0 : this.font.width(muted) + 4);
        String shownTitle = page == Page.SEARCH && !searchQuery.isEmpty()
                ? this.font.plainSubstrByWidth(title.getString(), titleRoom, true) // the end of a long query
                : fit(title.getString(), titleRoom);
        if (!muted.isEmpty()) {
            g.drawString(this.font, muted, right - 8 - this.font.width(muted), DISPLAY_Y + (HEADER_H - 8) / 2, 0xFF0A2A66, false);
        }
        g.drawString(this.font, shownTitle, left, DISPLAY_Y + (HEADER_H - 8) / 2, 0xFF0A2A66, false);
        if (LibraryCache.get().scanning() && page != Page.TRACKS && page != Page.CHAPTERS && page != Page.SETTINGS
                && page != Page.CONFIRM_RESET) {
            // a library scan is running: animated dots after the title
            String dots = ".".repeat(1 + (int) ((Util.getMillis() / 400) % 3));
            g.drawString(this.font, dots, left + this.font.width(shownTitle) + 2, DISPLAY_Y + (HEADER_H - 8) / 2, 0xFF0A2A66, false);
        }
        drawStatusIcon(g, right - 5);

        // Nothing to show (only the album and book lists can be empty)
        if (count == 0) {
            g.drawWordWrap(this.font, Component.translatable(page == Page.SEARCH
                            ? (searchQuery.isEmpty() ? "screen.icraft_music.ipod.search_hint" : "screen.icraft_music.ipod.no_results")
                            : LibraryCache.get().scanning() ? "screen.icraft_music.ipod.scanning"
                            : page == Page.ALBUMS && albumSection == AlbumSection.NAS ? "screen.icraft_music.ipod.no_nas"
                            : page != Page.BOOKS ? "screen.icraft_music.ipod.empty"
                            : isPodcastSection() ? "screen.icraft_music.ipod.no_podcasts"
                            : "screen.icraft_music.ipod.no_books"),
                    left, LIST_Y + 3, right - left, 0xFFFFFFFF);
        }

        // The rows
        for (int row = 0; row < ROWS; row++) {
            int index = scroll + row;
            if (index >= count) break;

            int rowY = LIST_Y + row * ROW_H;
            boolean isSelected = index == selected;
            if (isSelected) {
                g.fill(DISPLAY_X + 1, rowY, DISPLAY_X + DISPLAY_W - 1, rowY + ROW_H - 1, 0xEE3A3A3A);
            }

            int textX = left;
            int right2 = right; // where the name has to end
            Component name;
            switch (page) {
                case ALBUMS, BOOKS -> {
                    AlbumView album = (page == Page.ALBUMS ? shownAlbums() : shownBooks()).get(index);
                    drawCover(g, album, left, rowY + 1);
                    textX = left + THUMB + 3;
                    name = album.name();
                    if (album.detail() != null) { // right-aligned in grey, like a track's time
                        int detailW = this.font.width(album.detail());
                        g.drawString(this.font, album.detail(), right - detailW, rowY + 2,
                                isSelected ? 0xFFFFFFFF : 0xFFAAAAAA, false);
                        right2 = right - detailW - 3;
                    }
                }
                case TRACKS, CHAPTERS -> {
                    assert open != null;
                    Track track = open.tracks().get(index);
                    name = track.name();
                    String detail = track.detail(); // the saved time; the playing row shows the live one
                    if (track.item() != null && track.item().progressBook() != null) {
                        // read the saved time now, not when the list was built, so it never shows an old value
                        double saved = AudiobookProgress.get(track.item().progressBook(), track.item().progressKey());
                        detail = saved >= 1 ? formatTime(saved) : null;
                    }
                    if (track.item() != null) {
                        double live = IpodPlayer.itemSeconds(track.item().progressKey());
                        if (live >= 1) detail = formatTime(live);
                    }
                    if (detail != null) { // right-aligned in grey
                        int detailW = this.font.width(detail);
                        g.drawString(this.font, detail, right - detailW, rowY + 2,
                                isSelected ? 0xFFFFFFFF : 0xFFAAAAAA, false);
                        right2 = right - detailW - 3;
                    }
                }
                case SETTINGS -> name = settingRow(index);
                case CONFIRM_RESET -> name = Component.translatable(index == CONFIRM_CANCEL
                        ? "screen.icraft_music.ipod.reset_cancel" : "screen.icraft_music.ipod.reset_do");
                case SEARCH -> {
                    SearchEntry entry = results.get(index);
                    name = Component.literal(entry.label());
                    String tag = Component.translatable("screen.icraft_music.ipod.result_" + entry.tag()).getString();
                    int tagW = this.font.width(tag);
                    g.drawString(this.font, tag, right - tagW, rowY + 2, isSelected ? 0xFFFFFFFF : 0xFFAAAAAA, false);
                    right2 = right - tagW - 3;
                }
                default -> name = menuRow(menuIds().get(index));
            }

            int color = 0xFFFFFFFF;
            String full = name.getString();
            int available = right2 - textX;
            if (isSelected && this.font.width(full) > available) {
                // too long: slide the whole title through the row (clipped), pausing at both ends
                int offset = marqueeOffset(this.font.width(full) - available, index);
                Marquee.scissor(g, textX, rowY, right2, rowY + ROW_H);
                g.drawString(this.font, full, textX - offset, rowY + 2, color, false);
                g.disableScissor();
            } else {
                g.drawString(this.font, fit(full, available), textX, rowY + 2, color, !isSelected);
            }
        }

        // Volume bar along the bottom of the display
        int barY = LIST_Y + ROWS * ROW_H + 1;
        int barW = right - left;
        g.fill(left, barY, left + barW, barY + 2, 0x66000000);
        g.fill(left, barY, left + (int) (barW * IpodVolume.get()), barY + 2, 0xFFFFFFFF);
    }

    // ---- scrolling of a selected title that does not fit ----
    private int marqueeKey = -1;
    private long marqueeStart;

    // How far (in pixels) the title is moved left now; restarts when the selection or page changes
    private int marqueeOffset(int overflow, int index) {
        int key = java.util.Objects.hash(page, index, open == null ? 0 : System.identityHashCode(open));
        long now = Util.getMillis();
        if (key != marqueeKey) {
            marqueeKey = key;
            marqueeStart = now;
        }
        return Marquee.offset(marqueeStart, overflow);
    }

    // A small triangle while playing, two bars while paused, nothing when idle
    private void drawStatusIcon(GuiGraphics g, int x) {
        if (!IpodPlayer.isPlaying()) return;
        int color = 0xFF0A2A66;
        if (IpodPlayer.isPaused()) {
            g.fill(x, STATUS_ICON_Y, x + 2, STATUS_ICON_Y + 5, color);
            g.fill(x + 3, STATUS_ICON_Y, x + 5, STATUS_ICON_Y + 5, color);
        } else {
            for (int i = 0; i < 5; i++) {
                int width = Math.min(i, 4 - i) + 1;
                g.fill(x, STATUS_ICON_Y + i, x + width, STATUS_ICON_Y + i + 1, color);
            }
        }
    }

    private void drawCover(GuiGraphics g, AlbumView album, int x, int y) {
        ResourceLocation texture = album.cover() != null ? CoverArt.get(album.cover()) : null;
        if (texture != null) {
            g.blit(texture, x, y, IpodScreen.THUMB, IpodScreen.THUMB, 0, 0, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE, CoverArt.SIZE);
            return;
        }

        g.fill(x, y, x + IpodScreen.THUMB, y + IpodScreen.THUMB, 0xFF3A3A3A);
        if (album.icon() != null) {
            float scale = IpodScreen.THUMB / 16.0F;
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(scale, scale, 1.0F);
            g.renderItem(album.icon(), 0, 0);
            g.pose().popPose();
        } else {
            String name = album.name().getString();
            String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            g.drawCenteredString(this.font, initial, x + IpodScreen.THUMB / 2, y + IpodScreen.THUMB / 2 - 4, 0xAAAAAA);
        }
    }

    // Cuts text short with "..." so it fits in the given width
    private String fit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        return this.font.plainSubstrByWidth(text, maxWidth - this.font.width("...")) + "...";
    }

    // =====================================================================
    // The list: what is selected and what pressing things does
    // =====================================================================

    private int listSize() {
        return switch (page) {
            case MENU -> menuIds().size();
            case SETTINGS -> SETTING_ROWS;
            case CONFIRM_RESET -> 2;
            case SEARCH -> results.size();
            case ALBUMS -> shownAlbums().size();
            case BOOKS -> shownBooks().size();
            case TRACKS, CHAPTERS -> {
                assert open != null;
                yield open.tracks().size();
            }
        };
    }

    private static Component menuRow(int index) {
        return Component.translatable(switch (index) {
            case MENU_MUSIC -> "screen.icraft_music.ipod.music";
            case MENU_NAS -> "screen.icraft_music.ipod.nas";
            case MENU_SEARCH -> "screen.icraft_music.ipod.search";
            case MENU_MANUAL -> "screen.icraft_music.ipod.manual";
            case NAS_MUSIC -> "screen.icraft_music.ipod.music";
            case NAS_BOOKS -> "screen.icraft_music.ipod.audiobooks";
            case NAS_PODCASTS -> "screen.icraft_music.ipod.podcasts";
            case MENU_BOOKS -> "screen.icraft_music.ipod.audiobooks";
            case MENU_PODCASTS -> "screen.icraft_music.ipod.podcasts";
            default -> "screen.icraft_music.ipod.settings";
        });
    }

    // "Shuffle: Off", "Repeat: List"...
    private static Component settingRow(int index) {
        if (index == SETTING_SHUFFLE) {
            return Component.translatable("screen.icraft_music.ipod.setting",
                    Component.translatable("screen.icraft_music.ipod.shuffle"),
                    Component.translatable(IpodSettings.shuffle() ? "screen.icraft_music.ipod.on" : "screen.icraft_music.ipod.off"));
        }
        if (index == SETTING_BASS) {
            return Component.translatable("screen.icraft_music.ipod.setting",
                    Component.translatable("screen.icraft_music.ipod.bass"),
                    Component.translatable("screen.icraft_music.ipod.bass." + IpodSettings.bassBoost().name().toLowerCase(Locale.ROOT)));
        }
        if (index == SETTING_STEREO || index == SETTING_GAME_MUSIC || index == SETTING_DOWNLOADS
                || index == SETTING_DOCK_DISPLAY) {
            boolean on = index == SETTING_STEREO ? IpodSettings.stereoSpeakers()
                    : index == SETTING_GAME_MUSIC ? IpodSettings.silenceBackgroundMusic()
                    : index == SETTING_DOWNLOADS ? IpodSettings.downloadSharedFiles() : IpodSettings.dockDisplay();
            return Component.translatable("screen.icraft_music.ipod.setting",
                    Component.translatable(index == SETTING_STEREO ? "screen.icraft_music.ipod.stereo"
                            : index == SETTING_GAME_MUSIC ? "screen.icraft_music.ipod.game_music"
                            : index == SETTING_DOWNLOADS ? "screen.icraft_music.ipod.downloads" : "screen.icraft_music.ipod.dock_display"),
                    Component.translatable(on ? "screen.icraft_music.ipod.on" : "screen.icraft_music.ipod.off"));
        }
        if (index == SETTING_RESET) return Component.translatable("screen.icraft_music.ipod.reset");
        if (index == SETTING_WORLD) {
            return Component.translatable("screen.icraft_music.ipod.setting",
                    Component.translatable("screen.icraft_music.ipod.world"),
                    Component.translatable("screen.icraft_music.ipod.world." + IpodSettings.worldSounds().name().toLowerCase(Locale.ROOT)));
        }
        return Component.translatable("screen.icraft_music.ipod.setting",
                Component.translatable("screen.icraft_music.ipod.repeat"),
                Component.translatable("screen.icraft_music.ipod.repeat." + IpodSettings.repeat().name().toLowerCase(Locale.ROOT)));
    }

    private void goTo(Page next, int row) {
        page = next;
        selected = row;
        scroll = 0;
    }

    // Keeps the highlighted row inside the 4 visible rows
    private void keepSelectionVisible() {
        int count = listSize();
        selected = Mth.clamp(selected, 0, Math.max(0, count - 1));
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + ROWS) scroll = selected - ROWS + 1;
        scroll = Mth.clamp(scroll, 0, Math.max(0, count - ROWS));
    }

    private void move(int step) {
        selected = Mth.clamp(selected + step, 0, Math.max(0, listSize() - 1));
    }

    // The middle button (or Enter, or clicking a row): open the page / album, play the track, change the setting
    private void activate() {
        if (listSize() == 0) return;
        switch (page) {
            case MENU -> {
                int id = menuIds().get(selected);
                if (id == MENU_MANUAL) { // the full manual; Esc there comes back to this menu
                    assert this.minecraft != null;
                    this.minecraft.setScreen(new ManualScreen(this));
                    return;
                }
                if (id == MENU_NAS) { // open the NAS submenu
                    inNas = true;
                    goTo(Page.MENU, 0);
                    return;
                }
                goTo(switch (id) {
                    case MENU_MUSIC, NAS_MUSIC -> {
                        albumSection = id == NAS_MUSIC ? AlbumSection.NAS : AlbumSection.MUSIC;
                        yield Page.ALBUMS;
                    }
                    case MENU_BOOKS, MENU_PODCASTS, NAS_BOOKS, NAS_PODCASTS -> {
                        bookSection = switch (id) {
                            case MENU_PODCASTS -> BookSection.PODCASTS;
                            case NAS_BOOKS -> BookSection.NAS_AUDIOBOOKS;
                            case NAS_PODCASTS -> BookSection.NAS_PODCASTS;
                            default -> BookSection.AUDIOBOOKS;
                        };
                        yield Page.BOOKS;
                    }
                    case MENU_SEARCH -> Page.SEARCH;
                    default -> Page.SETTINGS;
                }, 0);
            }
            case ALBUMS -> {
                openedIndex = selected;
                open = shownAlbums().get(selected);
                goTo(Page.TRACKS, 0);
            }
            case BOOKS -> {
                openedIndex = selected;
                open = shownBooks().get(selected);
                goTo(Page.CHAPTERS, open.startRow());
            }
            case TRACKS, CHAPTERS -> playFromOpen();
            case SEARCH -> {
                SearchEntry entry = results.get(selected);
                cameFromSearch = true;
                searchSelected = selected;
                open = entry.view();
                Page target = entry.chapters() ? Page.CHAPTERS : Page.TRACKS;
                if (entry.row() < 0) {
                    goTo(target, entry.chapters() ? open.startRow() : 0); // the album/book/show: open its list
                } else {
                    goTo(target, entry.row()); // a track: open its album with this row selected and start from it
                    playFromOpen();
                }
            }
            case SETTINGS -> {
                if (selected == SETTING_SHUFFLE) {
                    IpodSettings.toggleShuffle();
                } else if (selected == SETTING_BASS) {
                    IpodSettings.cycleBassBoost();
                } else if (selected == SETTING_WORLD) {
                    IpodSettings.cycleWorldSounds();
                } else if (selected == SETTING_STEREO) {
                    IpodSettings.toggleStereoSpeakers();
                } else if (selected == SETTING_GAME_MUSIC) {
                    IpodSettings.toggleSilenceBackgroundMusic();
                } else if (selected == SETTING_DOWNLOADS) {
                    IpodSettings.toggleDownloadSharedFiles();
                } else if (selected == SETTING_DOCK_DISPLAY) {
                    IpodSettings.toggleDockDisplay();
                } else if (selected == SETTING_RESET) {
                    goTo(Page.CONFIRM_RESET, CONFIRM_CANCEL); // ask first
                } else {
                    IpodSettings.cycleRepeat();
                }
            }
            case CONFIRM_RESET -> {
                if (selected == CONFIRM_RESET_ROW) IpodSettings.resetToDefaults();
                goTo(Page.SETTINGS, SETTING_RESET);
            }
        }
    }

    // Starts the open album/book from the selected row (the discs of a docked iPod go through the dock)
    private void playFromOpen() {
        if (page == Page.TRACKS && dock != null) {
            assert open != null;
            if (open.icon() != null) {
                // the discs of a docked iPod: the dock (the server) plays them for everybody near it
                lastPlayingSeen = selected;
                DockControl.play(dock, selected);
                return;
            }
        }
        if (dock != null && dockOwner && DockControl.canShareFiles()) {
            assert open != null;
            if (open.tracks().stream().allMatch(t -> t.item() != null)) {
                // file sharing is on: the dock plays the list for everybody near it, the files go to the server
                playedAlbum = open;
                playedActions = null;
                lastPlayingSeen = selected;
                DockControl.playFiles(dock, open.tracks().stream().map(Track::item).toList(), selected, page == Page.CHAPTERS);
                return;
            }
        }
        assert open != null;
        List<Runnable> actions = open.tracks().stream().map(Track::play).toList();
        playedAlbum = open;
        playedActions = actions;
        lastPlayingSeen = selected;
        IpodPlayer.setQueueDock(dockOwner ? dock : null); // only the owner's files play through the dock
        IpodPlayer.playQueue(actions, selected, page == Page.CHAPTERS);
    }

    // The Menu button: one level up. The row we came from stays highlighted.
    private void menu() {
        switch (page) {
            case TRACKS, CHAPTERS -> {
                if (cameFromSearch) { // back to the results the album was opened from
                    cameFromSearch = false;
                    goTo(Page.SEARCH, searchSelected);
                } else {
                    goTo(page == Page.TRACKS ? Page.ALBUMS : Page.BOOKS, openedIndex);
                }
            }
            case SEARCH -> goTo(Page.MENU, menuRowOf(MENU_SEARCH));
            case ALBUMS -> {
                inNas = albumSection == AlbumSection.NAS;
                goTo(Page.MENU, menuRowOf(inNas ? NAS_MUSIC : MENU_MUSIC));
            }
            case BOOKS -> {
                inNas = isNasBookSection();
                goTo(Page.MENU, menuRowOf(switch (bookSection) {
                    case PODCASTS -> MENU_PODCASTS;
                    case NAS_AUDIOBOOKS -> NAS_BOOKS;
                    case NAS_PODCASTS -> NAS_PODCASTS;
                    case AUDIOBOOKS -> MENU_BOOKS;
                }));
            }
            case SETTINGS -> goTo(Page.MENU, menuRowOf(MENU_SETTINGS));
            case CONFIRM_RESET -> goTo(Page.SETTINGS, SETTING_RESET); // the Menu button cancels
            case MENU -> {
                if (inNas) { // back from the NAS submenu to the main menu
                    inNas = false;
                    goTo(Page.MENU, menuRowOf(MENU_NAS));
                }
            }
        }
    }

    // =====================================================================
    // Mouse and keyboard
    // =====================================================================

    // Turn a mouse position on the screen into iPod coordinates (0,0 = the iPod's top-left).
    // Only correct once the animation has finished, which is the only time we use it.
    private double localX(double mouseX) {
        return (mouseX - this.width / 2.0) / fitScale() + BODY_W / 2.0;
    }

    private double localY(double mouseY) {
        return (mouseY - this.height / 2.0) / fitScale() + BODY_H / 2.0;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !settled()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        double x = localX(mouseX);
        double y = localY(mouseY);

        // Where is the click relative to the wheel?
        double dx = x - WHEEL_X;
        double dy = y - WHEEL_Y;
        double distSq = dx * dx + dy * dy;
        boolean onMiddle = distSq <= (double) CENTER_R * CENTER_R;

        // Clicking anywhere else cancels a middle-button click that was still waiting
        if (!onMiddle) {
            pendingActivateAt = -1;
        }

        // A click on one of the rows
        if (x >= DISPLAY_X && x < DISPLAY_X + DISPLAY_W && y >= LIST_Y && y < LIST_Y + ROWS * ROW_H) {
            int index = scroll + (int) ((y - LIST_Y) / ROW_H);
            if (index < listSize()) {
                selected = index;
                activate();
            }
            return true;
        }

        // A click on the wheel
        if (distSq <= (double) WHEEL_R * WHEEL_R) {
            if (onMiddle) {
                onMiddleClick();
            } else if (Math.abs(dx) > Math.abs(dy)) {
                // left / right: what happens is decided when the button is released (see mouseReleased)
                hold = dx < 0 ? Hold.PREVIOUS : Hold.NEXT;
                holdStart = Util.getMillis();
                holdLastStep = holdStart;
                holdSkipped = false;
            } else {
                if (dy < 0) {
                    menu();                         // top
                } else {
                    IpodPlayer.togglePause();       // bottom
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // The middle button: one click opens/plays (after a short wait), two quick clicks mute or unmute
    private void onMiddleClick() {
        if (pendingActivateAt >= 0) {
            // A click was already waiting, so this is the second one: a double-click
            pendingActivateAt = -1;
            if (!IpodMute.toggleIpod(ipodId)) { // only for this player: this iPod goes silent and keeps running
                activate(); // nothing plays, so treat it as a normal click
            }
        } else {
            // First click: don't act yet, wait to see whether a second one follows
            pendingActivateAt = Util.getMillis() + DOUBLE_CLICK_MS;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!settled()) return false;
        double x = localX(mouseX);
        double y = localY(mouseY);

        // Over the display: move the highlight
        if (x >= DISPLAY_X && x < DISPLAY_X + DISPLAY_W && y >= DISPLAY_Y && y < DISPLAY_Y + DISPLAY_H) {
            move(scrollY > 0 ? -1 : 1);
            return true;
        }
        // Over the wheel: change the volume
        if (Math.hypot(x - WHEEL_X, y - WHEEL_Y) <= WHEEL_R) {
            IpodVolume.set(IpodVolume.get() + (float) scrollY * 0.05F);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (settled()) {
            if (keyCode == GLFW.GLFW_KEY_UP) {
                move(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                move(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                activate();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                boolean fresh = !backspaceDown; // a held key repeats; only a new press may go back a level
                backspaceDown = true;
                if (page == Page.SEARCH && !searchQuery.isEmpty()) { // delete a letter; with nothing typed it goes back
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    searchChanged();
                } else if (fresh) {
                    menu();
                }
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean backspaceDown;

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) backspaceDown = false;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    // Typing on the search page
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (settled() && page == Page.SEARCH && codePoint >= ' ' && codePoint != 127
                && searchQuery.length() < SEARCH_MAX_LENGTH) {
            searchQuery += codePoint;
            searchChanged();
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && hold != Hold.NONE) {
            Hold released = hold;
            hold = Hold.NONE;
            if (!holdSkipped) { // a quick click: change track
                if (released == Hold.PREVIOUS) {
                    IpodPlayer.previous();
                } else {
                    IpodPlayer.next();
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        hold = Hold.NONE;
        IpodVolume.save(); // write the volume and the settings to the config file when the screen closes
        IpodSettings.save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}