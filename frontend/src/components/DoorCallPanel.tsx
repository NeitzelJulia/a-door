import {useEffect, useRef, useState} from "react";

type Signal =
    | { event: "offer"; data: RTCSessionDescriptionInit }
    | { event: "answer"; data: RTCSessionDescriptionInit }
    | { event: "candidate"; data: RTCIceCandidateInit }
    | { event: "bye" };

export default function DoorCallPanel() {
    // --- Refs ---
    const wsRef = useRef<WebSocket | null>(null);
    const pcRef = useRef<RTCPeerConnection | null>(null);

    const localVideoRef = useRef<HTMLVideoElement | null>(null);
    const remoteAudioRef = useRef<HTMLAudioElement | null>(null);

    const localStreamRef = useRef<MediaStream | null>(null);

    // --- UI/State ---
    const [wsOpen, setWsOpen] = useState(false);
    const [avReady, setAvReady] = useState(false);
    const [ringing, setRinging] = useState(false);
    const [connected, setConnected] = useState(false);
    const [showPreview, setShowPreview] = useState(false);
    const [err, setErr] = useState<string | null>(null);
    const [iceConn, setIceConn] = useState<RTCIceConnectionState>("new");
    const [sigState, setSigState] = useState<RTCSignalingState>("stable");

    if (!import.meta.env.VITE_SIGNALING_WS_URL) {
        throw new Error("VITE_SIGNALING_WS_URL muss gesetzt sein!");
    }
    const WS_URL: string = import.meta.env.VITE_SIGNALING_WS_URL;

    // --- Small utils ---
    const setMediaStream = (el: HTMLMediaElement | null, stream: MediaStream | null) => {
        if (!el) return;
        el.srcObject = stream; // TS kennt srcObject -> kein Suppress nötig
    };

    const clearMediaStream = (el: HTMLMediaElement | null) => {
        if (!el) return;
        try { el.pause(); } catch {/* noop */}
        el.srcObject = null;
    };

    // --- Peer wiring ---
    const wirePeerHandlers = (pc: RTCPeerConnection) => {
        pc.onicecandidate = (ev) => {
            if (ev.candidate && wsRef.current?.readyState === WebSocket.OPEN) {
                wsRef.current.send(JSON.stringify({ event: "candidate", data: ev.candidate.toJSON() }));
            }
        };

        pc.onsignalingstatechange = () => setSigState(pc.signalingState);
        pc.oniceconnectionstatechange = () => {
            const s = pc.iceConnectionState;
            setIceConn(s);
            if (s === "connected" || s === "completed") {
                setConnected(true);
                setRinging(false);
            } else if (s === "failed" || s === "disconnected" || s === "closed") {
                setConnected(false);
            }
        };

        // Remote-Audio (Hub -> Door)
        pc.ontrack = (ev) => {
            const stream = ev.streams[0];
            setMediaStream(remoteAudioRef.current, stream);
            remoteAudioRef.current?.play().catch(() => {});
        };
    };

    const newPeer = () => {
        const pc = new RTCPeerConnection({
            iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
        });
        wirePeerHandlers(pc);
        return pc;
    };

    const cleanup = () => {
        setRinging(false);
        setConnected(false);
        setAvReady(false);

        try {
            localStreamRef.current?.getTracks().forEach((t) => t.stop());
            localStreamRef.current = null;
        } catch {/* noop */}
        try {
            pcRef.current?.close();
        } catch {/* noop */}

        // frische PC-Instanz
        pcRef.current = newPeer();

        // Vorschau räumen
        clearMediaStream(localVideoRef.current);
    };

    // --- Bootstrap: WS + PeerConnection ---
    useEffect(() => {
        const ws = new WebSocket(WS_URL);
        wsRef.current = ws;
        ws.onopen = () => setWsOpen(true);
        ws.onclose = () => setWsOpen(false);

        const pc = newPeer();
        pcRef.current = pc;

        ws.onmessage = async (e) => {
            const msg = JSON.parse(e.data) as Signal;
            const currentPc = pcRef.current;
            if (!currentPc) return;

            if (msg.event === "answer") {
                try {
                    if (!currentPc.currentRemoteDescription) {
                        await currentPc.setRemoteDescription(new RTCSessionDescription(msg.data));
                    }
                } catch (err) {
                    console.warn("setRemoteDescription(answer) failed:", err);
                }
            } else if (msg.event === "candidate") {
                try {
                    await currentPc.addIceCandidate(new RTCIceCandidate(msg.data));
                } catch (err) {
                    console.warn("addIceCandidate (door) failed:", err);
                }
            } else if (msg.event === "bye") {
                cleanup();
            }
        };

        const beforeUnload = () => {
            try { ws.close(); } catch {/* noop */}
            try { pc.getSenders().forEach((s) => s.track?.stop()); pc.close(); } catch {/* noop */}
        };
        window.addEventListener("beforeunload", beforeUnload);
        return () => {
            window.removeEventListener("beforeunload", beforeUnload);
            beforeUnload();
        };
    }, [WS_URL]);

    // --- Vorschau (nachträglich toggelbar) ---
    useEffect(() => {
        const vid = localVideoRef.current;
        const stream = localStreamRef.current;
        if (!vid) return;

        if (showPreview && stream) {
            setMediaStream(vid, stream);
            vid.muted = true;
            vid.play().catch(() => {});
        } else {
            clearMediaStream(vid);
        }
    }, [showPreview]);

    // --- Aktionen ---
    const ring = async () => {
        setErr(null);

        const supported = !!navigator.mediaDevices?.getUserMedia;
        if (!supported) {
            setErr(
                window.isSecureContext
                    ? "getUserMedia wird nicht unterstützt."
                    : "getUserMedia erfordert HTTPS oder http://localhost."
            );
            return;
        }
        if (!pcRef.current || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
            setErr("WebSocket/PeerConnection nicht bereit.");
            return;
        }

        try {
            // Remote-Audio vorab „blessen“
            const a = remoteAudioRef.current;
            if (a) {
                a.muted = false; // Tür soll Hub-Stimme hören
                a.play().catch(() => {});
            }

            // A/V holen
            const stream = await navigator.mediaDevices.getUserMedia({
                video: true,
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true,
                },
            });
            localStreamRef.current = stream;

            // Tracks nur einmal hinzufügen
            if (pcRef.current.getSenders().length === 0) {
                stream.getTracks().forEach((t) => pcRef.current!.addTrack(t, stream));
            }

            // Vorschau ggf. aktivieren
            const vid = localVideoRef.current;
            if (vid && showPreview) {
                setMediaStream(vid, stream);
                vid.muted = true;
                await vid.play().catch(() => {});
            }

            // Offer erzeugen & senden
            const offer = await pcRef.current.createOffer();
            await pcRef.current.setLocalDescription(offer);
            wsRef.current.send(JSON.stringify({ event: "offer", data: offer }));

            setAvReady(true);
            setRinging(true);
        } catch (e) {
            const msg = e instanceof Error ? `${e.name}: ${e.message}` : String(e);
            setErr(`Klingeln fehlgeschlagen: ${msg}`);
        }
    };

    const hangup = () => {
        try {
            wsRef.current?.send(JSON.stringify({ event: "bye" }));
        } catch {/* noop */}
        cleanup();
    };

    // --- UI ---
    return (
        <div style={{ display: "grid", gap: 12, maxWidth: 560 }}>
            <h3 style={{ margin: 0 }}>a-door — Klingel & Offerer</h3>

            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                <button onClick={ring} disabled={!wsOpen || ringing}>
                    🔔 Klingeln
                </button>
                <label style={{ display: "flex", alignItems: "center", gap: 6 }}>
                    <input
                        type="checkbox"
                        checked={showPreview}
                        onChange={(e) => setShowPreview(e.target.checked)}
                    />
                    Video-Vorschau zeigen
                </label>
                <button onClick={hangup} disabled={!ringing && !connected}>
                    Auflegen
                </button>
            </div>

            {showPreview && (
                <video
                    ref={localVideoRef}
                    autoPlay
                    playsInline
                    muted
                    style={{ width: "100%", background: "#000", borderRadius: 6 }}
                />
            )}

            {/* Unsichtbares Audio für Hub->Door Gegensprechen */}
            <audio ref={remoteAudioRef} autoPlay playsInline />

            <div style={{ fontFamily: "monospace", fontSize: 12, lineHeight: 1.5 }}>
                <div>WS: <strong>{wsOpen ? "connected" : "disconnected"}</strong></div>
                <div>Signaling: <strong>{sigState}</strong></div>
                <div>ICE: <strong>{iceConn}</strong></div>
                <div>Call: <strong>{connected ? "connected" : (ringing ? "ringing" : "idle")}</strong></div>
                <div>A/V: <strong>{avReady ? "ready" : "idle"}</strong></div>
            </div>

            {err && (
                <div style={{ color: "#f66", fontFamily: "monospace", fontSize: 12 }}>
                    {err}
                </div>
            )}
        </div>
    );
}
