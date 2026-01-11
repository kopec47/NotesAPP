package com.example.notes

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notes.ui.theme.NotesTheme
import com.google.firebase.database.*
import com.google.firebase.auth.FirebaseAuth

@IgnoreExtraProperties
data class Note(
    val id: String? = null,
    val subject: String? = null,
    val type: String? = null,
    val content: String? = null
)

class MainActivity : ComponentActivity(), SensorEventListener {


    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private lateinit var shakeDetector: ShakeDetector

    // Firebase
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    // Stan aplikacji
    private var isDarkTheme by mutableStateOf(false)

    // Stan zalogowania użytkownika. Null = jeszcze nie zalogowany/ładuje się
    private var currentUserUid by mutableStateOf<String?>(null)

    private var editingNoteId: String? = null
    // Referencja do bazy, która będzie ustawiana dynamicznie
    private var activeNotesRef: DatabaseReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Inicjalizacja Sensorów
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (lightSensor == null) {
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        }

        // 2. Inicjalizacja Firebase Auth i Bazy
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance("https://notess-73e6e-default-rtdb.firebaseio.com")

        // 3. Konfiguracja ShakeDetectora
        shakeDetector = ShakeDetector {
            // Usuwam tylko jeśli mam ID notatki I jestem zalogowany
            if (editingNoteId != null && activeNotesRef != null) {
                activeNotesRef!!.child(editingNoteId!!).removeValue()
                editingNoteId = null
                Toast.makeText(this, "Notatka usunięta potrząśnięciem!", Toast.LENGTH_SHORT).show()
            }
        }


        checkUserAndSignIn()

        setContent {

            val darkThemeFinal = if (lightSensor == null) isSystemInDarkTheme() else isDarkTheme

            NotesTheme(darkTheme = darkThemeFinal) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    if (currentUserUid != null && activeNotesRef != null) {
                        NotesApp(notesRef = activeNotesRef!!) { id ->
                            editingNoteId = id
                        }
                    } else {

                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Logowanie do bezpiecznej bazy...", modifier = Modifier.padding(top = 64.dp))
                        }
                    }
                }
            }
        }
    }

    private fun checkUserAndSignIn() {
        val user = auth.currentUser
        if (user != null) {

            setupDatabaseForUser(user.uid)
        } else {

            auth.signInAnonymously().addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val newUid = auth.currentUser!!.uid
                    setupDatabaseForUser(newUid)
                } else {
                    Toast.makeText(this, "Błąd autoryzacji: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupDatabaseForUser(uid: String) {

        // Zapisuje notatki w: notes -> UID_UŻYTKOWNIKA -> notatki
        activeNotesRef = database.getReference("notes").child(uid)

        // Aktualizuje stan Compose, żeby odświeżyć widok
        currentUserUid = uid
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelerometer?.let { sensorManager.registerListener(shakeDetector, it, SensorManager.SENSOR_DELAY_UI) }
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


val brandGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF6A1B9A), Color(0xFFAB47BC))
)

@Composable
fun NotesApp(notesRef: DatabaseReference, onEditingNoteChanged: (String?) -> Unit) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Add) }
    var editingNote by remember { mutableStateOf<Note?>(null) }


    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (currentScreen) {
                Screen.Add -> NotesAddScreen(notesRef)
                Screen.List -> NotesListScreen(notesRef) { note ->
                    editingNote = note
                    currentScreen = Screen.Edit
                    onEditingNoteChanged(note.id)
                }
                Screen.Edit -> NotesEditScreen(notesRef, editingNote) {
                    currentScreen = Screen.List
                    editingNote = null
                    onEditingNoteChanged(null)
                }
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
        subject, type, content,
        onSubjectChange = { subject = it },
        onTypeChange = { type = it },
        onContentChange = { content = it },
        onSaveClick = {
            if (subject.isNotBlank() && type.isNotBlank() && content.isNotBlank()) {
                val newId = notesRef.push().key ?: return@NoteFormScreen
                val note = Note(newId, subject, type, content)
                notesRef.child(newId).setValue(note)
                    .addOnSuccessListener {
                        info = "✓ Notatka zapisana!"
                        subject = ""; type = ""; content = ""
                    }.addOnFailureListener { e -> info = "Błąd: ${e.message}" }
            } else info = "Uzupełnij wszystkie pola"
        },
        onDeleteClick = null,
        info = info,
        title = "Nowa Notatka"
    )
}

@Composable
fun NotesEditScreen(notesRef: DatabaseReference, note: Note?, onBack: () -> Unit) {
    var subject by remember { mutableStateOf(note?.subject ?: "") }
    var type by remember { mutableStateOf(note?.type ?: "") }
    var content by remember { mutableStateOf(note?.content ?: "") }
    var info by remember { mutableStateOf("") }

    LaunchedEffect(note?.id) {
        note?.id?.let { id ->
            notesRef.child(id).get().addOnSuccessListener { snapshot ->
                snapshot.getValue(Note::class.java)?.let {
                    subject = it.subject ?: ""
                    type = it.type ?: ""
                    content = it.content ?: ""
                }
            }
        }
    }

    NoteFormScreen(
        subject, type, content,
        onSubjectChange = { subject = it },
        onTypeChange = { type = it },
        onContentChange = { content = it },
        onSaveClick = {
            note?.id?.let { noteId ->
                val updated = Note(noteId, subject, type, content)
                notesRef.child(noteId).setValue(updated)
                    .addOnSuccessListener {
                        info = "✓ Zaktualizowano!"
                        onBack()
                    }.addOnFailureListener { e -> info = "Błąd: ${e.message}" }
            } ?: run { info = "Brak ID notatki!" }
        },
        onDeleteClick = {
            note?.id?.let { noteId ->
                notesRef.child(noteId).removeValue()
                onBack()
            }
        },
        info = info,
        title = "Edycja"
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
    onDeleteClick: (() -> Unit)?,
    info: String,
    title: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(32.dp))


        OutlinedTextField(
            value = subject,
            onValueChange = onSubjectChange,
            label = { Text("Przedmiot") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = type,
            onValueChange = onTypeChange,
            label = { Text("Typ (np. praca)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))


        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            label = { Text("Treść") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            shape = RoundedCornerShape(16.dp),
            maxLines = 5
        )

        Spacer(Modifier.height(32.dp))

        // Przyciski
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

            Button(
                onClick = onSaveClick,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brandGradient)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ZAPISZ", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            if (onDeleteClick != null) {
                OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("USUŃ")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if(info.isNotEmpty()) {
            Text(info, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun NotesListScreen(notesRef: DatabaseReference, onEditNote: (Note) -> Unit) {
    var notes by remember { mutableStateOf(listOf<Note>()) }
    var loading by remember { mutableStateOf(true) }

    DisposableEffect(notesRef) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                notes = snapshot.children.mapNotNull { child ->
                    val note = child.getValue(Note::class.java)
                    note?.copy(id = child.key)
                }
                loading = false
            }

            override fun onCancelled(error: DatabaseError) {
                loading = false
            }
        }
        notesRef.addValueEventListener(listener)
        onDispose { notesRef.removeEventListener(listener) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (notes.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Brak notatek",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Kliknij 'Dodaj' na dole", color = Color.Gray)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp)
            ) {
                item {
                    Text(
                        "Twoja Lista",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                    )
                }
                items(notes) { note ->

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditNote(note) }
                            .shadow(8.dp, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {

                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .fillMaxHeight()
                                    .background(brandGradient)
                            )

                            // Treść karty
                            Column(
                                modifier = Modifier
                                    .padding(20.dp)
                                    .weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = note.subject ?: "Bez tytułu",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    text = note.type?.uppercase() ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = note.content ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BottomBar(currentScreen: Screen, onAddClick: () -> Unit, onListClick: () -> Unit) {

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(70.dp)
            .shadow(16.dp, RoundedCornerShape(35.dp)),
        shape = RoundedCornerShape(35.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Przycisk DODAJ
            IconButton(
                onClick = onAddClick,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Dodaj",
                        tint = if (currentScreen == Screen.Add) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(32.dp)
                    )
                    if(currentScreen == Screen.Add) {
                        Text("Dodaj", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }


            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(30.dp)
                    .background(Color.LightGray)
            )


            IconButton(
                onClick = onListClick,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.List,
                        contentDescription = "Lista",
                        tint = if (currentScreen == Screen.List || currentScreen == Screen.Edit) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(32.dp)
                    )
                    if(currentScreen == Screen.List || currentScreen == Screen.Edit) {
                        Text("Lista", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}