package org.betonquest.betonquest.modules.schedule;

import lombok.CustomLog;
import org.betonquest.betonquest.api.bukkit.config.custom.unmodifiable.UnmodifiableConfigurationSection;
import org.betonquest.betonquest.api.config.QuestPackage;
import org.betonquest.betonquest.api.schedule.Schedule;
import org.betonquest.betonquest.api.schedule.Scheduler;
import org.betonquest.betonquest.exceptions.InstructionParseException;
import org.betonquest.betonquest.exceptions.ObjectNotFoundException;
import org.betonquest.betonquest.id.ScheduleID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Class responsible for managing schedule types, their schedulers, as well as parsing schedules from config.
 */
@CustomLog(topic = "Schedules")
public class EventScheduling {

    /**
     * Map that contains all types of schedulers,
     * with keys being their names and values holding the scheduler and schedule class.
     */
    private final Map<String, ScheduleType<?>> scheduleTypes = new HashMap<>();

    /**
     * Default Constructor
     */
    public EventScheduling() {
    }

    /**
     * Register a new type of schedule with its name, the class used to create new instances of the schedule and a scheduler
     * that provides the scheduling logic.
     *
     * @param type      name of the schedule type, used to define the schedule in the config
     * @param schedule  class object of the schedule type
     * @param scheduler scheduler for managing the schedules
     * @param <S>       generic, the schedule class
     */
    public <S extends Schedule> void registerScheduleType(final String type, final Class<S> schedule, final Scheduler<S> scheduler) {
        scheduleTypes.put(type, new ScheduleType<>(schedule, scheduler));
    }

    /**
     * Method used for loading all schedules of a quest package and registering them in the correct schedulers.
     *
     * @param questPackage package to load
     */
    public void loadData(final QuestPackage questPackage) {
        final ConfigurationSection configuration = questPackage.getConfig().getConfigurationSection("schedules");
        if (configuration == null) {
            return;
        }
        for (final String key : configuration.getKeys(false)) {
            if (key.contains(" ")) {
                LOG.warn(questPackage, "Schedule name cannot contain spaces: '" + key + "' (in " + questPackage + " package)");
                continue;
            }

            try {
                final ScheduleID scheduleID = new ScheduleID(questPackage, key);
                try {
                    final ConfigurationSection scheduleConfig = new UnmodifiableConfigurationSection(
                            questPackage.getConfig().getSourceConfigurationSection("schedules." + scheduleID.getBaseID())
                    );
                    final String type = Optional.ofNullable(scheduleConfig.getString("type"))
                            .orElseThrow(() -> new InstructionParseException("Missing type instruction"));
                    //FIXME FIX OR WORK AROUND TYPE ERASURE
                    final ScheduleType scheduleType = Optional.ofNullable(scheduleTypes.get(type))
                            .orElseThrow(() -> new InstructionParseException("The schedule type '" + type + "' is not defined"));
                    final Schedule schedule = scheduleType.newScheduleInstance(scheduleID, scheduleConfig);
                    scheduleType.scheduler.schedule(schedule);
                } catch (final InstructionParseException | InvalidConfigurationException e) {
                    LOG.warn(questPackage, "Error loading schedule '" + scheduleID + "':" + e.getMessage(), e);
                } catch (final InvocationTargetException | NoSuchMethodException | InstantiationException |
                               IllegalAccessException e) {
                    LOG.reportException(questPackage, e);
                }
            } catch (final ObjectNotFoundException e) {
                LOG.warn(questPackage, "Cannot load schedule with name '" + key + "' (in " + questPackage + " package): " + e.getMessage(), e);
            }
        }
    }

    /**
     * Start all schedulers and activate all schedules.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void startAll() {
        for (final ScheduleType<?> type : scheduleTypes.values()) {
            try {
                type.scheduler.start();
            } catch (final Exception e) {
                LOG.error("Error while enabling " + type.scheduler + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Stop all schedulers and disable all schedules.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void stopAll() {
        for (final ScheduleType<?> type : scheduleTypes.values()) {
            try {
                type.scheduler.stop();
            } catch (final Exception e) {
                LOG.error("Error while enabling " + type.scheduler + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Helper class that holds all implementations needed for a specific schedule type.
     *
     * @param scheduleClass class of the schedule
     * @param scheduler     instance of the scheduler
     * @param <S>           type of the schedule.
     */
    private record ScheduleType<S extends Schedule>(Class<S> scheduleClass, Scheduler<S> scheduler) {
        private S newScheduleInstance(final ScheduleID scheduleID, final ConfigurationSection scheduleConfig)
                throws InstructionParseException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
            try {
                return scheduleClass
                        .getConstructor(ScheduleID.class, ConfigurationSection.class)
                        .newInstance(scheduleID, scheduleConfig);
            } catch (final InvocationTargetException e) {
                if (e.getCause() instanceof final InstructionParseException cause) {
                    throw cause;
                } else {
                    throw e;
                }
            }
        }
    }
}
