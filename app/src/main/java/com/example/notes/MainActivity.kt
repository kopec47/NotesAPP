package com.example.notes

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.notes.ui.theme.NotesTheme
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.ValueEventListener
import com.example.notes.ShakeDetector

@IgnoreExtraProperties
data class Note(
    val id: String? = null,
    val subject: String? = null,
    val type: String? = null,
    val content: String? = null
)

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var database: FirebaseDatabase
    private lateinit var notesRef: DatabaseReference

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null

    private var isDarkTheme by mutableStateOf(false)

    private lateinit var shakeDetector: ShakeDetector
    private var accelerometer: Sensor? = null
    private var editingNoteId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Firebase
        database = FirebaseDatabase.getInstance("https://notess-73e6e-default-rtdb.firebaseio.com")
        notesRef = database.getReference("notes")

        // SensorManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        // Shake detector
        shakeDetector = ShakeDetector {
            editingNoteId?.let { noteId ->
                notesRef.child(noteId).removeValue()
                editingNoteId = null
            }
        }

        setContent {
            val darkThemeFinal = if (lightSensor == null) isSystemInDarkTheme() else isDarkTheme

            NotesTheme(darkTheme = darkThemeFinal) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NotesApp(
                        notesRef = notesRef,
                        onEditingNoteChanged = { editingNoteId = it }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelerometer?.let {
            sensorManager.registerListener(shakeDetector, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        sensorManager.unregisterListener(shakeDetector)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_LIGHT) {
                val lux = it.values[0]
                Log.d("LIGHT", "lux = $lux")

                isDarkTheme = when {
                    lux < 20f -> true
                    lux > 40f -> false
                    else -> isDarkTheme
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
fun NotesApp(
    notesRef: DatabaseReference,
    onEditingNoteChanged: (String?) -> Unit
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Add) }
    var editingNote by remember { mutableStateOf<Note?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (currentScreen) {
                Screen.Add -> NotesAddScreen(notesRef = notesRef)
                Screen.List -> NotesListScreen(
                    notesRef = notesRef,
                    onEditNote = { note ->
                        editingNote = note
                        currentScreen = Screen.Edit
                        onEditingNoteChanged(note.id)
                    }
                )
                Screen.Edit -> NotesEditScreen(
                    notesRef = notesRef,
                    note = editingNote,
                    onBack = {
                        currentScreen = Screen.List
                        editingNote = null
                        onEditingNoteChanged(null)
                    }
                )
            }
        }

        BottomBar(
            currentScreen = currentScreen,
            onAddClick = { currentScreen = Screen.Add },
            onListClick = { currentScreen = Screen.List }
        )
    }
}

enum class Screen { Add, List, Edit }

@Composable
fun NotesAddScreen(notesRef: DatabaseReference) {
    var subject by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }

    NoteFormScreen(
        subject = subject,
        type = type,
        content = content,
        onSubjectChange = { subject = it },
        onTypeChange = { type = it },
        onContentChange = { content = it },
        onSaveClick = {
            if (subject.isNotBlank() && type.isNotBlank() && content.isNotBlank()) {
                val newId = notesRef.push().key ?: return@NoteFormScreen
                val note = Note(id = newId, subject = subject, type = type, content = content)
                notesRef.child(newId).setValue(note)
                    .addOnSuccessListener {
                        info = "✓ Notatka zapisana!"
                        subject = ""; type = ""; content = ""
                    }
                    .addOnFailureListener { error -> info = "Błąd: ${error.message}" }
            } else info = "Uzupełnij wszystkie pola"
        },
        info = info,
        title = "Dodaj notatkę"
    )
}

@Composable
fun NotesEditScreen(
    notesRef: DatabaseReference,
    note: Note?,
    onBack: () -> Unit
) {
    var subject by remember { mutableStateOf(note?.subject ?: "") }
    var type by remember { mutableStateOf(note?.type ?: "") }
    var content by remember { mutableStateOf(note?.content ?: "") }
    var info by remember { mutableStateOf("") }

    LaunchedEffect(note?.id) {
        note?.id?.let { id ->
            notesRef.child(id).get().addOnSuccessListener { snapshot ->
                val loadedNote = snapshot.getValue(Note::class.java)
                loadedNote?.let {
                    subject = it.subject ?: ""
                    type = it.type ?: ""
                    content = it.content ?: ""
                }
            }
        }
    }

    NoteFormScreen(
        subject = subject,
        type = type,
        content = content,
        onSubjectChange = { subject = it },
        onTypeChange = { type = it },
        onContentChange = { content = it },
        onSaveClick = {
            note?.id?.let { noteId ->
                val updatedNote = Note(id = noteId, subject = subject, type = type, content = content)
                notesRef.child(noteId).setValue(updatedNote)
                    .addOnSuccessListener {
                        info = "✓ Notatka zaktualizowana!"
                        onBack()
                    }
                    .addOnFailureListener { error -> info = "Błąd zapisu: ${error.message}" }
            } ?: run { info = "Brak ID notatki!" }
        },
        onDeleteClick = {
            note?.id?.let { noteId ->
                notesRef.child(noteId).removeValue()
                    .addOnSuccessListener {
                        info = "✓ Notatka usunięta!"
                        onBack()
                    }
                    .addOnFailureListener { error -> info = "Błąd usunięcia: ${error.message}" }
            } ?: run { info = "Brak ID notatki!" }
        },
        info = info,
        title = "Edytuj notatkę"
    )
}

@Composable
fun NoteFormScreen(
    subject: String,
    type: String,
    content: String,
    onSubjectChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    info: String,
    title: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(value = subject, onValueChange = onSubjectChange, label = { Text("Przedmiot") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(value = type, onValueChange = onTypeChange, label = { Text("Typ notatki") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(value = content, onValueChange = onContentChange, label = { Text("Treść notatki") }, modifier = Modifier.fillMaxWidth().height(120.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSaveClick, modifier = Modifier.weight(1f)) { Text("Zapisz") }
            if (onDeleteClick != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onDeleteClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) { Text("Usuń") }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = info)
    }
}

@Composable
fun NotesListScreen(
    notesRef: DatabaseReference,
    onEditNote: (Note) -> Unit
) {
    var notes by remember { mutableStateOf(listOf<Note>()) }
    var loading by remember { mutableStateOf(true) }

    DisposableEffect(notesRef) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val noteList = mutableListOf<Note>()
                for (child in snapshot.children) {
                    val key = child.key
                    val note = child.getValue(Note::class.java)
                    note?.copy(id = key)?.let { noteList.add(it) }
                }
                notes = noteList
                loading = false
            }

            override fun onCancelled(error: DatabaseError) { loading = false }
        }

        notesRef.addValueEventListener(listener)
        onDispose { notesRef.removeEventListener(listener) }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        else if (notes.isEmpty()) Text("Brak notatek.\nDodaj pierwszą!", modifier = Modifier.align(Alignment.Center))
        else LazyColumn {
            items(notes) { note ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onEditNote(note) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = note.subject ?: "Brak przedmiotu", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Typ: ${note.type ?: "Nieznany"}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = note.content ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun BottomBar(
    currentScreen: Screen,
    onAddClick: () -> Unit,
    onListClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onAddClick, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dodaj")
            }
        }

        TextButton(onClick = onListClick, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.List, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Notatki")
            }
        }
    }
}