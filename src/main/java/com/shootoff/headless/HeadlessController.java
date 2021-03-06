/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.headless;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraErrorView;
import com.shootoff.camera.CameraFactory;
import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.Shot;
import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.courses.Course;
import com.shootoff.courses.io.CourseIO;
import com.shootoff.gui.CalibrationConfigurator;
import com.shootoff.gui.CalibrationManager;
import com.shootoff.gui.CalibrationOption;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.ExerciseListener;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.Resetter;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TargetView;
import com.shootoff.gui.pane.ArenaBackgroundsSlide;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.headless.protocol.AddTargetMessage;
import com.shootoff.headless.protocol.ClearCourseMessage;
import com.shootoff.headless.protocol.ConfigurationData;
import com.shootoff.headless.protocol.CurrentBackgroundsMessage;
import com.shootoff.headless.protocol.CurrentConfigurationMessage;
import com.shootoff.headless.protocol.CurrentCoursesMessage;
import com.shootoff.headless.protocol.CurrentExercisesMessage;
import com.shootoff.headless.protocol.CurrentTargetsMessage;
import com.shootoff.headless.protocol.ErrorMessage;
import com.shootoff.headless.protocol.ErrorMessage.ErrorType;
import com.shootoff.headless.protocol.GetBackgroundsMessage;
import com.shootoff.headless.protocol.GetConfigurationMessage;
import com.shootoff.headless.protocol.GetCoursesMessage;
import com.shootoff.headless.protocol.GetExercisesMessage;
import com.shootoff.headless.protocol.GetTargetsMessage;
import com.shootoff.headless.protocol.Message;
import com.shootoff.headless.protocol.MessageListener;
import com.shootoff.headless.protocol.MoveTargetMessage;
import com.shootoff.headless.protocol.NewShotMessage;
import com.shootoff.headless.protocol.RemoveTargetMessage;
import com.shootoff.headless.protocol.ResetMessage;
import com.shootoff.headless.protocol.ResizeTargetMessage;
import com.shootoff.headless.protocol.SetBackgroundMessage;
import com.shootoff.headless.protocol.SetConfigurationMessage;
import com.shootoff.headless.protocol.SetCourseMessage;
import com.shootoff.headless.protocol.SetExerciseMessage;
import com.shootoff.headless.protocol.TargetMessage;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.TrainingExerciseBase;
import com.shootoff.plugins.TrainingExerciseView;
import com.shootoff.plugins.engine.Plugin;
import com.shootoff.plugins.engine.PluginEngine;
import com.shootoff.plugins.engine.PluginListener;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.Target;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import marytts.util.io.FileFilter;

public class HeadlessController implements CameraErrorView, Resetter, ExerciseListener, CalibrationConfigurator,
		QRCodeListener, ConnectionListener, MessageListener, TrainingExerciseView {
	private static final Logger logger = LoggerFactory.getLogger(HeadlessController.class);

	private final Configuration config;
	private final CamerasSupervisor camerasSupervisor;
	private final Map<UUID, Target> targets = new HashMap<>();
	private final Set<TrainingExercise> trainingExercises = new HashSet<>();
	private final Set<TrainingExercise> projectorTrainingExercises = new HashSet<>();

	private PluginEngine pluginEngine;
	private ProjectorArenaPane arenaPane;
	private CanvasManager arenaCanvasManager;
	private Target qrCodeTarget;

	private Optional<HeadlessServer> server = Optional.empty();

	public HeadlessController() {
		config = Configuration.getConfig();
		camerasSupervisor = new CamerasSupervisor(config);

		final Map<String, Camera> configuredCameras = config.getWebcams();
		final Optional<Camera> camera;

		if (configuredCameras.isEmpty()) {
			camera = CameraFactory.getDefault();
		} else {
			camera = Optional.of(configuredCameras.values().iterator().next());
		}

		if (!camera.isPresent()) {
			logger.error("There are no cameras attached to the computer.");
			return;
		}

		final Camera c = camera.get();

		if (c.isLocked() && !c.isOpen()) {
			logger.error("Default camera is locked, cannot proceed");
			return;
		}

		initializePluginEngine();

		final ObservableList<ShotEntry> shotEntries = FXCollections.observableArrayList();
		final CanvasManager canvasManager = new CanvasManager(new Group(), this, "Default", shotEntries);
		final CameraManager cameraManager = camerasSupervisor.addCameraManager(c, this, canvasManager);

		final Stage arenaStage = new Stage();
		// TODO: Pass controls added to this pane to the device controlling
		// SBC
		final Pane trainingExerciseContainer = new Pane();

		arenaPane = new ProjectorArenaPane(arenaStage, null, trainingExerciseContainer, this, shotEntries);

		arenaCanvasManager = arenaPane.getCanvasManager();

		shotEntries.addListener(new ListChangeListener<ShotEntry>() {
			@Override
			public void onChanged(Change<? extends ShotEntry> change) {
				if (!server.isPresent() || !change.next() || change.getAddedSize() < 1) return;

				for (ShotEntry entry : change.getAddedSubList()) {
					final Shot shot = entry.getShot();
					server.get().sendMessage(
							new NewShotMessage(shot.getColor(), shot.getX(), shot.getY(), shot.getTimestamp()));
				}
			}
		});

		arenaStage.setTitle("Projector Arena");
		arenaStage.setScene(new Scene(arenaPane));
		arenaStage.setFullScreenExitHint("");

		// TODO: Camera views to non-null value to handle calibration issues
		final CalibrationManager calibrationManager = new CalibrationManager(this, cameraManager, arenaPane, null,
				this);

		arenaPane.setCalibrationManager(calibrationManager);
		arenaPane.toggleArena();
		arenaPane.autoPlaceArena();

		calibrationManager.enableCalibration();
	}

	private void initializePluginEngine() {
		try {
			pluginEngine = new PluginEngine(new PluginListener() {
				@Override
				public void registerExercise(TrainingExercise exercise) {
					trainingExercises.add(exercise);
				}

				@Override
				public void registerProjectorExercise(TrainingExercise exercise) {
					projectorTrainingExercises.add(exercise);
				}

				@Override
				public void unregisterExercise(TrainingExercise exercise) {
					if (exercise instanceof ProjectorTrainingExerciseBase) {
						projectorTrainingExercises.remove(exercise);
					} else {
						trainingExercises.remove(exercise);
					}
				}
			});
			pluginEngine.startWatching();
		} catch (IOException e) {
			logger.error("Failed to start plugin engine", e);
		}
	}

	@Override
	public void reset() {
		camerasSupervisor.reset();
	}

	@Override
	public void showCameraLockError(Camera webcam, boolean allCamerasFailed) {
		if (!server.isPresent()) return;

		final String messageFormat;

		if (allCamerasFailed) {
			messageFormat = "Cannot open the webcam %s. It is being "
					+ "used by another program or it is an IPCam with the wrong credentials. This "
					+ "is the only configured camera, thus ShootOFF must close.";
		} else {
			messageFormat = "Cannot open the webcam %s. It is being "
					+ "used by another program, it is an IPCam with the wrong credentials, or you "
					+ "have ShootOFF open more than once.";
		}

		final Optional<String> webcamName = config.getWebcamsUserName(webcam);
		final String message = String.format(messageFormat,
				webcamName.isPresent() ? webcamName.get() : webcam.getName());

		server.get().sendMessage(new ErrorMessage(message, ErrorType.TARGET));
	}

	@Override
	public void showMissingCameraError(Camera webcam) {
		sendCameraError(webcam, CameraErrorView.MISSING_ERROR);
	}

	@Override
	public void showFPSWarning(Camera webcam, double fps) {
		sendCameraError(webcam, CameraErrorView.FPS_WARNING, fps);
	}

	@Override
	public void showBrightnessWarning(Camera webcam) {
		sendCameraError(webcam, CameraErrorView.BRIGHTNESS_WARNING);
	}

	private void sendCameraError(Camera webcam, String format, Object... args) {
		if (server.isPresent()) {
			final Optional<String> cameraUserName = config.getWebcamsUserName(webcam);
			final String cameraName;
			if (cameraUserName.isPresent()) {
				cameraName = cameraUserName.get();
			} else {
				cameraName = webcam.getName();
			}

			final List<Object> argsList = new ArrayList<Object>(Arrays.asList(args));
			argsList.add(0, cameraName);

			server.get().sendMessage(new ErrorMessage(String.format(format, argsList.toArray()), ErrorType.CAMERA));
		}
	}

	@Override
	public void setProjectorExercise(TrainingExercise exercise) {
		try {
			config.setExercise(null);

			final Constructor<?> ctor = exercise.getClass().getConstructor(List.class);
			final TrainingExercise newExercise = (TrainingExercise) ctor.newInstance(arenaCanvasManager.getTargets());

			final Optional<Plugin> plugin = pluginEngine.getPlugin(newExercise);
			if (plugin.isPresent()) {
				config.setPlugin(plugin.get());
			} else {
				config.setPlugin(null);
			}

			config.setExercise(newExercise);

			final Runnable initExercise = () -> {
				((ProjectorTrainingExerciseBase) newExercise).init(config, camerasSupervisor, this, arenaPane);
				newExercise.init();
			};

			if (Platform.isFxApplicationThread()) {
				initExercise.run();
			} else {
				Platform.runLater(initExercise);
			}

		} catch (final ReflectiveOperationException e) {
			final ExerciseMetadata metadata = exercise.getInfo();
			logger.error("Failed to start projector exercise " + metadata.getName() + " " + metadata.getVersion(), e);
		}
	}

	@Override
	public void setExercise(TrainingExercise exercise) {
		try {
			// If there is a current exercise, ensure it is destroyed
			// before starting an new one in case it's a projector
			// exercise that added targets that need to be removed.
			config.setExercise(null);

			if (exercise == null) return;

			final Constructor<?> ctor = exercise.getClass().getConstructor(List.class);

			final TrainingExercise newExercise = (TrainingExercise) ctor.newInstance(arenaCanvasManager.getTargets());

			final Optional<Plugin> plugin = pluginEngine.getPlugin(newExercise);
			if (plugin.isPresent()) {
				config.setPlugin(plugin.get());
			} else {
				config.setPlugin(null);
			}

			config.setExercise(newExercise);

			final Runnable initExercise = () -> {
				((TrainingExerciseBase) newExercise).init(config, camerasSupervisor, this);
				newExercise.init();
			};

			if (Platform.isFxApplicationThread()) {
				initExercise.run();
			} else {
				Platform.runLater(initExercise);
			}
		} catch (final ReflectiveOperationException e) {
			final ExerciseMetadata metadata = exercise.getInfo();
			logger.error("Failed to start exercise " + metadata.getName() + " " + metadata.getVersion(), e);
		}
	}

	@Override
	public Configuration getConfiguration() {
		return config;
	}

	@Override
	public PluginEngine getPluginEngine() {
		return pluginEngine;
	}

	@Override
	public CalibrationOption getCalibratedFeedBehavior() {
		return CalibrationOption.ONLY_IN_BOUNDS;
	}

	@Override
	public void calibratedFeedBehaviorsChanged() {}

	@Override
	public void toggleCalibrating(boolean isCalibrating) {
		if (!isCalibrating) {
			final HeadlessServer headlessServer = new BluetoothServer(this);
			server = Optional.of(headlessServer);
			headlessServer.startReading(this, this);
		}
	}

	@Override
	public void qrCodeCreated(Image qrCodeImage) {
		final Group targetGroup = new Group();
		final ImageRegion qrCodeRegion = new ImageRegion(qrCodeImage);
		qrCodeRegion.getAllTags().put(TargetView.TAG_IGNORE_HIT, "true");

		targetGroup.getChildren().add(qrCodeRegion);

		qrCodeTarget = arenaCanvasManager.addTarget(null, targetGroup, new HashMap<String, String>(), false);
	}

	@Override
	public void connectionEstablished() {
		if (qrCodeTarget != null) {
			arenaCanvasManager.removeTarget(qrCodeTarget);
			qrCodeTarget = null;
		}
	}

	@Override
	public void messageReceived(Message message) {
		if (message instanceof ClearCourseMessage) {
			arenaPane.getCanvasManager().clearTargets();
		} else if (message instanceof GetBackgroundsMessage) {
			if (server.isPresent())
				server.get().sendMessage(new CurrentBackgroundsMessage(ArenaBackgroundsSlide.DEFAULT_BACKGROUNDS));
		} else if (message instanceof GetConfigurationMessage) {
			sendConfiguration();
		} else if (message instanceof GetCoursesMessage) {
			sendCourses();
		} else if (message instanceof GetExercisesMessage) {
			sendExercises();
		} else if (message instanceof GetTargetsMessage) {
			sendTargets();
		} else if (message instanceof ResetMessage) {
			reset();
		} else if (message instanceof SetBackgroundMessage) {
			final SetBackgroundMessage backgroundMessage = (SetBackgroundMessage) message;

			if (backgroundMessage.getName().isEmpty() && backgroundMessage.getResourceName().isEmpty()) {
				arenaPane.setArenaBackground(null);
			}

			final String resourceName = backgroundMessage.getResourceName();

			if (ArenaBackgroundsSlide.DEFAULT_BACKGROUNDS.containsKey(backgroundMessage.getName())
					&& ArenaBackgroundsSlide.DEFAULT_BACKGROUNDS.get(backgroundMessage.getName())
							.equals(resourceName)) {
				final InputStream is = ArenaBackgroundsSlide.class.getResourceAsStream(resourceName);
				arenaPane.setArenaBackground(new LocatedImage(is, resourceName));
			} else {
				if (server.isPresent()) {
					server.get().sendMessage(new ErrorMessage(
							"Background " + backgroundMessage.getName() + " " + resourceName + " does not exist.",
							ErrorType.BACKGROUND));
				}
			}
		} else if (message instanceof SetConfigurationMessage) {
			final SetConfigurationMessage configMessage = (SetConfigurationMessage) message;
			setConfiguration(configMessage.getConfigurationData());
		} else if (message instanceof SetCourseMessage) {
			final SetCourseMessage courseMessage = (SetCourseMessage) message;
			final File courseFile = new File(
					System.getProperty("shootoff.courses") + File.separator + courseMessage.getCourse().toString());

			if (courseFile.exists()) {
				final Optional<Course> course = CourseIO.loadCourse(arenaPane, courseFile);

				if (course.isPresent()) {
					arenaPane.setCourse(course.get());
				}
			} else {
				if (server.isPresent()) server.get().sendMessage(
						new ErrorMessage("Course " + courseMessage.getCourse() + " does not exist.", ErrorType.COURSE));
			}
		} else if (message instanceof SetExerciseMessage) {
			final SetExerciseMessage exerciseMessage = (SetExerciseMessage) message;
			setExercise(exerciseMessage.getNewExercise());
		} else if (message instanceof TargetMessage) {
			handleTargetMessage((TargetMessage) message);
		}
	}

	private void sendConfiguration() {
		if (server.isPresent()) {
			final ConfigurationData configurationData = new ConfigurationData(config.getMarkerRadius(),
					config.ignoreLaserColor(), config.getIgnoreLaserColorName(), config.useVirtualMagazine(),
					config.getVirtualMagazineCapacity(), config.useMalfunctions(), config.getMalfunctionsProbability(),
					config.showArenaShotMarkers());
			server.get().sendMessage(new CurrentConfigurationMessage(configurationData));
		}
	}

	private void sendCourses() {
		if (!server.isPresent()) return;

		final java.io.FileFilter folderFilter = new java.io.FileFilter() {
			@Override
			public boolean accept(File path) {
				return path.isDirectory();
			}
		};

		final File coursesDirectory = new File(System.getProperty("shootoff.courses"));
		final File[] courseFolders = coursesDirectory.listFiles(folderFilter);

		final FilenameFilter courseFilter = new FilenameFilter() {
			@Override
			public boolean accept(File directory, String fileName) {
				return fileName.endsWith(".course");
			}
		};

		final Map<String, List<String>> courses = new HashMap<>();
		for (File courseFolder : courseFolders) {
			final List<String> courseFileNames = new ArrayList<>();
			for (File courseFile : courseFolder.listFiles(courseFilter)) {
				courseFileNames.add(courseFile.getName());
			}

			courses.put(courseFolder.getName(), courseFileNames);
		}

		server.get().sendMessage(new CurrentCoursesMessage(courses));
	}

	private void sendExercises() {
		if (server.isPresent()) {
			final Set<ExerciseMetadata> trainingExercisesMetadata = new HashSet<ExerciseMetadata>();
			final Set<ExerciseMetadata> projectorTrainingExercisesMetadata = new HashSet<ExerciseMetadata>();
			trainingExercises.stream().map(TrainingExercise::getInfo).forEach(trainingExercisesMetadata::add);
			projectorTrainingExercises.stream().map(TrainingExercise::getInfo)
					.forEach(projectorTrainingExercisesMetadata::add);
			server.get().sendMessage(
					new CurrentExercisesMessage(trainingExercisesMetadata, projectorTrainingExercisesMetadata));
		}
	}

	private void sendTargets() {
		if (!server.isPresent()) return;

		final String shootOffHome = System.getProperty("shootoff.home") + File.separator;
		final File targetsFolder = new File(shootOffHome + "targets");

		final File[] targetFiles = targetsFolder.listFiles(new FileFilter("target"));

		if (targetFiles != null) {
			final File[] relativeTargetFiles = new File[targetFiles.length];

			for (int i = 0; i < targetFiles.length; i++) {
				relativeTargetFiles[i] = new File(targetFiles[i].toString().replaceAll(shootOffHome, ""));
			}

			Arrays.sort(relativeTargetFiles);
			server.get().sendMessage(new CurrentTargetsMessage(Arrays.asList(relativeTargetFiles)));
		} else {
			server.get().sendMessage(new ErrorMessage("Failed to find target files", ErrorType.TARGET));
		}
	}

	private void setConfiguration(ConfigurationData configurationData) {
		config.setMarkerRadius(configurationData.getMarkerRadius());

		config.setIgnoreLaserColor(configurationData.isIgnoreLaserColor());
		config.setIgnoreLaserColorName(configurationData.getIgnoreLaserColorName());

		config.setUseVirtualMagazine(configurationData.useVirtualMagazine());
		config.setVirtualMagazineCapacity(configurationData.getVirtualMagazineCapacity());

		config.setMalfunctions(configurationData.useMalfunctions());
		config.setMalfunctionsProbability(configurationData.getMalfunctionsProbability());

		config.setShowArenaShotMarkers(configurationData.showArenaShotMarkers());

		try {
			config.writeConfigurationFile();
		} catch (ConfigurationException | IOException e) {
			logger.error("Failed to save headless configuration", e);
		}
	}

	private void setExercise(ExerciseMetadata exerciseMetadata) {
		logger.debug("Setting exercise with metadata {}", exerciseMetadata);

		if (exerciseMetadata.getCreator().isEmpty() && exerciseMetadata.getDescription().isEmpty()
				&& exerciseMetadata.getName().isEmpty() && exerciseMetadata.getVersion().isEmpty()) {
			setExercise((TrainingExercise) null);
			logger.trace("Exercise unset");
			return;
		}
		
		for (TrainingExercise exercise : trainingExercises) {
			if (exercise.getInfo().equals(exerciseMetadata)) {
				logger.trace("Setting exercise to {}", exercise.getInfo().toString());
				setExercise(exercise);
				return;
			}
		}

		for (TrainingExercise exercise : projectorTrainingExercises) {
			if (exercise.getInfo().equals(exerciseMetadata)) {
				logger.trace("Setting projector exercise to {}", exercise.getInfo().toString());
				setProjectorExercise(exercise);
				return;
			}
		}
	}

	private void handleTargetMessage(TargetMessage message) {
		if (message instanceof AddTargetMessage) {
			final AddTargetMessage addTarget = (AddTargetMessage) message;
			final Optional<Target> target = arenaCanvasManager.addTarget(addTarget.getTargetFile());

			if (target.isPresent()) {
				targets.put(addTarget.getUuid(), target.get());
			}
		} else {
			final UUID targetUuid = message.getUuid();
			if (!targets.containsKey(targetUuid)) {
				final String errorMessage = String.format(
						"A target with UUID {} does not exist to perform operation {}", targetUuid,
						message.getClass().getName());

				if (server.isPresent()) {
					server.get().sendMessage(new ErrorMessage(errorMessage, ErrorType.TARGET));
				}

				logger.error(errorMessage);
				return;
			}

			final Target t = targets.get(targetUuid);

			if (message instanceof MoveTargetMessage) {
				final MoveTargetMessage moveTarget = (MoveTargetMessage) message;
				t.setPosition(moveTarget.getNewX(), moveTarget.getNewY());
			} else if (message instanceof ResizeTargetMessage) {
				final ResizeTargetMessage resizeTarget = (ResizeTargetMessage) message;
				t.setDimensions(resizeTarget.getNewWidth(), resizeTarget.getNewHeight());
			} else if (message instanceof RemoveTargetMessage) {
				arenaCanvasManager.removeTarget(t);
				targets.remove(targetUuid);
			}
		}
	}

	@Override
	public Pane getTrainingExerciseContainer() {
		// TODO Use a stub that sends controls to tablet
		return null;
	}

	@Override
	public TableView<ShotEntry> getShotEntryTable() {
		// TODO Use a stub that sends column data to tablet
		return new TableView<ShotEntry>();
	}

	@Override
	public VBox getButtonsPane() {
		// TODO Use a stub that sends controls to tablet
		return null;
	}

	@Override
	public Optional<CameraView> getArenaView() {
		return Optional.empty();
	}

	@Override
	public List<Target> getTargets() {
		return arenaCanvasManager.getTargets();
	}
}
