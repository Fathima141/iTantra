package com.itantra.app

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Firestore-backed relay. Each device listens to its own inbox and sends
 * packets to the friend's inbox. Location, when present, travels as part of
 * the same emergency packet.
 */
class Network(
    private val myId: String,
    private val onPacket: (Packet) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val db = FirebaseFirestore.getInstance()
    private var registration: ListenerRegistration? = null

    fun listen() {
        registration?.remove()

        registration = db.collection("inbox")
            .document(myId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onStatus("Listen error: ${error.message}")
                    return@addSnapshotListener
                }

                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val d = change.document

                        val packet = Packet(
                            language = d.getString("language") ?: "en",
                            priority = (d.getLong("priority") ?: 0L).toInt(),
                            sequence = d.getLong("sequence") ?: 0L,
                            timestamp = d.getLong("timestamp") ?: 0L,
                            latitude = d.getDouble("latitude"),
                            longitude = d.getDouble("longitude"),
                            message = d.getString("message") ?: ""
                        )

                        onPacket(packet)

                        // Consume after processing so the same message is not replayed.
                        d.reference.delete()
                    }
                }
            }

        onStatus("Listening as $myId")
    }

    fun send(friendId: String, packet: Packet) {
        if (friendId.isBlank()) {
            onStatus("Enter your friend's ID first")
            return
        }

        val data = hashMapOf<String, Any>(
            "language" to packet.language,
            "priority" to packet.priority,
            "sequence" to packet.sequence,
            "timestamp" to packet.timestamp,
            "message" to packet.message
        )

        packet.latitude?.let { data["latitude"] = it }
        packet.longitude?.let { data["longitude"] = it }

        db.collection("inbox")
            .document(friendId)
            .collection("messages")
            .add(data)
            .addOnSuccessListener {
                onStatus(
                    if (packet.priority > 0 && packet.latitude != null && packet.longitude != null)
                        "Emergency + location sent to $friendId"
                    else
                        "Sent to $friendId"
                )
            }
            .addOnFailureListener { e ->
                onStatus("Send failed: ${e.message}")
            }
    }

    fun stop() {
        registration?.remove()
        registration = null
    }
}
