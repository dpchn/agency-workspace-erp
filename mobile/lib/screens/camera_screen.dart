import 'dart:async';

import 'package:camera/camera.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import '../core/l10n.dart';

/// In-app camera that takes the picture by itself after a 3-2-1 countdown, so a worker who has never used
/// a camera app does not need to find a shutter or confirm anything. A big shutter button takes it at once.
/// Pops with the JPEG bytes, or null when cancelled. Throws [CameraUnavailable] when there is no usable camera.
class CameraScreen extends StatefulWidget {
  final bool front;
  final int countdown;
  const CameraScreen({super.key, required this.front, this.countdown = 3});
  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class CameraUnavailable implements Exception {
  final Object cause;
  CameraUnavailable(this.cause);
  @override
  String toString() => 'Camera unavailable: $cause';
}

class _CameraScreenState extends State<CameraScreen> {
  CameraController? _c;
  int? _left;
  Timer? _timer;
  bool _taking = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    try {
      final cams = await availableCameras();
      if (cams.isEmpty) throw CameraUnavailable('no camera');
      final want = widget.front ? CameraLensDirection.front : CameraLensDirection.back;
      final cam = cams.firstWhere((c) => c.lensDirection == want, orElse: () => cams.first);
      // medium (~720x480) keeps selfies ~100 KB; team photos need more detail
      final c = CameraController(cam, widget.front ? ResolutionPreset.medium : ResolutionPreset.high,
          enableAudio: false, imageFormatGroup: ImageFormatGroup.jpeg);
      await c.initialize();
      if (!mounted) {
        await c.dispose();
        return;
      }
      setState(() {
        _c = c;
        _left = widget.countdown;
      });
      _timer = Timer.periodic(const Duration(seconds: 1), (t) {
        if (!mounted) return;
        if ((_left ?? 0) <= 1) {
          t.cancel();
          _take();
        } else {
          setState(() => _left = _left! - 1);
          HapticFeedback.selectionClick();
        }
      });
    } catch (e) {
      if (mounted) Navigator.pop(context, CameraUnavailable(e));
    }
  }

  Future<void> _take() async {
    final c = _c;
    if (c == null || _taking || !c.value.isInitialized) return;
    _timer?.cancel();
    setState(() {
      _taking = true;
      _left = null;
    });
    try {
      final x = await c.takePicture();
      final bytes = await x.readAsBytes();
      HapticFeedback.mediumImpact();
      if (mounted) Navigator.pop(context, bytes);
    } catch (e) {
      if (mounted) Navigator.pop(context, CameraUnavailable(e));
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _c?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = _c;
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Stack(fit: StackFit.expand, children: [
          if (c != null && c.value.isInitialized) Center(child: CameraPreview(c)) else const Center(child: CircularProgressIndicator(color: Colors.white)),
          Positioned(
            top: 12,
            left: 12,
            right: 12,
            child: Text(tr(widget.front ? 'look_camera' : 'team_in_frame'),
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.bold, shadows: [Shadow(blurRadius: 6)])),
          ),
          if (_left != null)
            Center(
              child: Text('$_left',
                  style: const TextStyle(color: Colors.white, fontSize: 140, fontWeight: FontWeight.bold, shadows: [Shadow(blurRadius: 12)])),
            ),
          Positioned(
            bottom: 24,
            left: 24,
            right: 24,
            child: Row(children: [
              IconButton.filledTonal(
                iconSize: 32,
                onPressed: () => Navigator.pop(context),
                icon: const Icon(Icons.close),
                tooltip: tr('cancel'),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: SizedBox(
                  height: 72,
                  child: FilledButton.icon(
                    onPressed: c == null || _taking ? null : _take,
                    icon: const Icon(Icons.photo_camera, size: 32),
                    label: Text(tr('take_photo'), style: const TextStyle(fontSize: 20)),
                  ),
                ),
              ),
            ]),
          ),
        ]),
      ),
    );
  }
}
