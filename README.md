# Notes

A minimalist note-taking Android app designed specifically for E-Ink displays with interactive checkbox support and rich text formatting.

## Features

- **E-Ink Optimized UI**: Clean, minimal interface designed for optimal readability on E-Ink displays
- **Rich Text Formatting**: Automatic title formatting with bold styling for the first line
- **Interactive Checklists**: 
  - Tap checkbox buttons to add new checklist items (☐)
  - Tap existing checkboxes to toggle between checked (☑) and unchecked (☐) states
  - Automatic strikethrough formatting for completed items
- **SQLite Database Storage**: Local storage for all notes with automatic timestamping
- **Bulk Operations**: Select multiple notes for deletion with selection mode
- **Auto-save**: Notes are automatically saved when navigating away from the editor
- **Empty Note Cleanup**: Automatically removes notes with no content

## Screenshots

*Add screenshots of your app here*

## Requirements

- Android 12+ (API level 31+)
- No special permissions required

## Installation

1. Clone the repository:
```bash
git clone https://github.com/yourusername/notes.git
cd Notes
```

2. Open the project in Android Studio

3. Build and run the app on your Android device

## Architecture

### Key Components

- **MainActivity**: Main activity displaying the notes list with RecyclerView
- **NoteActivity**: Note editor activity with rich text editing capabilities
- **DatabaseHelper**: SQLite database management for CRUD operations
- **NoteEditText**: Custom EditText with checkbox functionality and text formatting
- **NoteAdapter**: RecyclerView adapter handling note display and selection
- **Note**: Data class representing a note entity

### Database Schema

```sql
CREATE TABLE notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    content TEXT,
    timestamp TEXT NOT NULL
);
```

### Technology Stack

- **Language**: Kotlin
- **UI Framework**: Android Views with ViewBinding support
- **Database**: SQLite with custom DatabaseHelper
- **Architecture**: Traditional Android architecture with SQLite integration
- **Text Processing**: Custom EditText with Spannable text formatting

## Usage

### Creating Notes
1. Tap the "+" button to create a new note
2. The first line automatically becomes the title (formatted in bold)
3. Press Enter and continue typing for the content
4. Notes are auto-saved when navigating away

### Adding Checklists
1. In the note editor, tap the checkbox button (☐) to add a new checklist item
2. Type your task after the checkbox symbol
3. Tap the checkbox to toggle between checked and unchecked states
4. Completed items automatically show strikethrough formatting

### Managing Notes
1. Tap any note in the list to edit it
2. Use the edit button to enter selection mode
3. Select multiple notes and tap delete to remove them
4. Empty notes are automatically deleted

### Text Formatting
- **Title**: First line is automatically formatted in bold
- **Checkboxes**: Use ☐ for unchecked and ☑ for checked items
- **Strikethrough**: Automatically applied to checked checklist items

## Dependencies

### Core Android
- androidx.core:core-ktx
- androidx.lifecycle:lifecycle-runtime-ktx
- androidx.appcompat:appcompat
- androidx.constraintlayout:constraintlayout
- androidx.recyclerview:recyclerview

### UI Components
- com.google.android.material:material:1.12.0
- androidx.activity:activity-compose
- androidx.compose.* (for future Compose integration)

### Database
- androidx.room:room-common-jvm
- androidx.room:room-runtime-jvm

### Testing
- junit:junit (Unit testing)
- androidx.test.ext:junit (Instrumented testing)
- androidx.test.espresso:espresso-core (UI testing)

## File Structure

```
app/src/main/java/com/example/notes/
├── MainActivity.kt              # Main notes list activity
├── NoteActivity.kt             # Note editing activity
├── DatabaseHelper.kt           # SQLite database operations
├── Note.kt                     # Note data class
├── NoteAdapter.kt              # RecyclerView adapter
├── NoteEditText.kt             # Custom EditText with formatting
├── Task.kt                     # Task data class
├── TaskAdapter.kt              # Task adapter (if used)
└── ui/theme/                   # Theme-related files
    ├── Color.kt
    ├── Theme.kt
    └── Type.kt
```

## Customization

### Checkbox Symbols
You can customize the checkbox symbols by modifying the constants in `NoteEditText.kt`:
```kotlin
private const val CHECKBOX_UNCHECKED = "☐ "
private const val CHECKBOX_CHECKED = "☑ "
```

### Theme Customization
The app uses a custom theme named `Theme.EInkNotes` optimized for E-Ink displays. Modify the theme files in `ui/theme/` to customize colors and typography.

### Database Schema
To add new fields to notes, update the `DatabaseHelper.kt` schema and increment the `DATABASE_VERSION`.

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Troubleshooting

### Common Issues

**Notes not saving**
- Ensure you navigate away from the note editor (tap Done or use back button)
- Check device storage space

**Checkboxes not working**
- Make sure you're tapping directly on the checkbox symbols (☐ or ☑)
- For empty checkboxes, tap anywhere on the line to start editing

**Text formatting issues**
- The title formatting applies only to the first line before any newline character
- Strikethrough only applies to lines starting with checked checkboxes (☑)

**App crashes on startup**
- Clear app data in Android Settings > Apps > Notes > Storage
- This will reset the database but remove all notes

**Selection mode not working**
- Tap the edit button (pencil icon) to enter selection mode
- Tap it again to exit selection mode

## Roadmap

- [ ] Note categories and tags
- [ ] Search functionality
- [ ] Export notes to text files
- [ ] Dark mode theme
- [ ] Note sharing capabilities
- [ ] Backup and restore functionality

## Contact

For questions or support, please open an issue on GitHub.