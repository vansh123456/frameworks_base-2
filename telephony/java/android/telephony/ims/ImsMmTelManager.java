/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telephony.ims;


import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.ITelephony;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A manager for the MmTel (Multimedia Telephony) feature of an IMS network, given an associated
 * subscription.
 *
 * Allows a user to query the IMS MmTel feature information for a subscription, register for
 * registration and MmTel capability status callbacks, as well as query/modify user settings for the
 * associated subscription.
 *
 * @see #createForSubscriptionId(int)
 * @hide
 */
@SystemApi
public class ImsMmTelManager implements RegistrationManager {

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "WIFI_MODE_", value = {
            WIFI_MODE_WIFI_ONLY,
            WIFI_MODE_CELLULAR_PREFERRED,
            WIFI_MODE_WIFI_PREFERRED
            })
    public @interface WiFiCallingMode {}

    /**
     * Register for IMS over IWLAN if WiFi signal quality is high enough. Do not hand over to LTE
     * registration if signal quality degrades.
     */
    public static final int WIFI_MODE_WIFI_ONLY = 0;

    /**
     * Prefer registering for IMS over LTE if LTE signal quality is high enough.
     */
    public static final int WIFI_MODE_CELLULAR_PREFERRED = 1;

    /**
     * Prefer registering for IMS over IWLAN if possible if WiFi signal quality is high enough.
     */
    public static final int WIFI_MODE_WIFI_PREFERRED = 2;

    /**
     * Callback class for receiving IMS network Registration callback events.
     * @see #registerImsRegistrationCallback(Executor, RegistrationCallback) (RegistrationCallback)
     * @see #unregisterImsRegistrationCallback(RegistrationCallback)
     * @deprecated Use {@link RegistrationManager.RegistrationCallback} instead.
     */
    // Do not add to this class, add to RegistrationManager.RegistrationCallback instead.
    @Deprecated
    public static class RegistrationCallback extends RegistrationManager.RegistrationCallback {

        /**
         * Notifies the framework when the IMS Provider is registered to the IMS network.
         *
         * @param imsTransportType the radio access technology.
         */
        @Override
        public void onRegistered(@AccessNetworkConstants.TransportType int imsTransportType) {
        }

        /**
         * Notifies the framework when the IMS Provider is trying to register the IMS network.
         *
         * @param imsTransportType the radio access technology.
         */
        @Override
        public void onRegistering(@AccessNetworkConstants.TransportType int imsTransportType) {
        }

        /**
         * Notifies the framework when the IMS Provider is deregistered from the IMS network.
         *
         * @param info the {@link ImsReasonInfo} associated with why registration was disconnected.
         */
        @Override
        public void onUnregistered(@Nullable ImsReasonInfo info) {
        }

        /**
         * A failure has occurred when trying to handover registration to another technology type.
         *
         * @param imsTransportType The transport type that has failed to handover registration to.
         * @param info A {@link ImsReasonInfo} that identifies the reason for failure.
         */
        @Override
        public void onTechnologyChangeFailed(
                @AccessNetworkConstants.TransportType int imsTransportType,
                @Nullable ImsReasonInfo info) {
        }
    }

    /**
     * Receives IMS capability status updates from the ImsService. This information is also
     * available via the {@link #isAvailable(int, int)} method below.
     *
     * @see #registerMmTelCapabilityCallback(Executor, CapabilityCallback) (CapabilityCallback)
     * @see #unregisterMmTelCapabilityCallback(CapabilityCallback)
     */
    public static class CapabilityCallback {

        private static class CapabilityBinder extends IImsCapabilityCallback.Stub {

            private final CapabilityCallback mLocalCallback;
            private Executor mExecutor;

            CapabilityBinder(CapabilityCallback c) {
                mLocalCallback = c;
            }

            @Override
            public void onCapabilitiesStatusChanged(int config) {
                if (mLocalCallback == null) return;

                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() -> mLocalCallback.onCapabilitiesStatusChanged(
                                new MmTelFeature.MmTelCapabilities(config))));
            }

            @Override
            public void onQueryCapabilityConfiguration(int capability, int radioTech,
                    boolean isEnabled) {
                // This is not used for public interfaces.
            }

            @Override
            public void onChangeCapabilityConfigurationError(int capability, int radioTech,
                    @ImsFeature.ImsCapabilityError int reason) {
                // This is not used for public interfaces
            }

            private void setExecutor(Executor executor) {
                mExecutor = executor;
            }
        }

        private final CapabilityBinder mBinder = new CapabilityBinder(this);

        /**
         * The status of the feature's capabilities has changed to either available or unavailable.
         * If unavailable, the feature is not able to support the unavailable capability at this
         * time.
         *
         * This information can also be queried using the {@link #isAvailable(int, int)} API.
         *
         * @param capabilities The new availability of the capabilities.
         */
        public void onCapabilitiesStatusChanged(
                @NonNull MmTelFeature.MmTelCapabilities capabilities) {
        }

        /**@hide*/
        public final IImsCapabilityCallback getBinder() {
            return mBinder;
        }

        /**@hide*/
        // Only exposed as public method for compatibility with deprecated ImsManager APIs.
        // TODO: clean up dependencies and change back to private visibility.
        public final void setExecutor(Executor executor) {
            mBinder.setExecutor(executor);
        }
    }

    private final int mSubId;

    /**
     * Create an instance of {@link ImsMmTelManager} for the subscription id specified.
     *
     * @param subId The ID of the subscription that this ImsMmTelManager will use.
     * @see android.telephony.SubscriptionManager#getActiveSubscriptionInfoList()
     * @throws IllegalArgumentException if the subscription is invalid.
     */
    public static @NonNull ImsMmTelManager createForSubscriptionId(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        return new ImsMmTelManager(subId);
    }

    /**
     * Only visible for testing, use {@link #createForSubscriptionId(int)} instead.
     * @hide
     */
    @VisibleForTesting
    public ImsMmTelManager(int subId) {
        mSubId = subId;
    }

    /**
     * Registers a {@link RegistrationCallback} with the system, which will provide registration
     * updates for the subscription specified in {@link #createForSubscriptionId(int)}. Use
     * {@link SubscriptionManager.OnSubscriptionsChangedListener} to listen to Subscription changed
     * events and call {@link #unregisterImsRegistrationCallback(RegistrationCallback)} to clean up.
     *
     * When the callback is registered, it will initiate the callback c to be called with the
     * current registration state.
     *
     * @param executor The executor the callback events should be run on.
     * @param c The {@link RegistrationCallback} to be added.
     * @see #unregisterImsRegistrationCallback(RegistrationCallback)
     * @throws IllegalArgumentException if the subscription associated with this callback is not
     * active (SIM is not inserted, ESIM inactive) or invalid, or a null {@link Executor} or
     * {@link CapabilityCallback} callback.
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     * @deprecated Use {@link #registerImsRegistrationCallback(
     * RegistrationManager.RegistrationCallback, Executor)} instead.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerImsRegistrationCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RegistrationCallback c) throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        c.setExecutor(executor);
        try {
            getITelephony().registerImsRegistrationCallback(mSubId, c.getBinder());
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new ImsException(e.getMessage(), e.errorCode);
            }
        } catch (RemoteException | IllegalStateException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**{@inheritDoc}*/
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerImsRegistrationCallback(
            @NonNull RegistrationManager.RegistrationCallback c,
            @NonNull @CallbackExecutor Executor executor) throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        c.setExecutor(executor);
        try {
            getITelephony().registerImsRegistrationCallback(mSubId, c.getBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException | IllegalStateException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Removes an existing {@link RegistrationCallback}.
     *
     * When the subscription associated with this callback is removed (SIM removed, ESIM swap,
     * etc...), this callback will automatically be removed. If this method is called for an
     * inactive subscription, it will result in a no-op.
     *
     * @param c The {@link RegistrationCallback} to be removed.
     * @see SubscriptionManager.OnSubscriptionsChangedListener
     * @see #registerImsRegistrationCallback(Executor, RegistrationCallback)
     * @deprecated Use {@link #unregisterImsRegistrationCallback(
     * RegistrationManager.RegistrationCallback)}.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterImsRegistrationCallback(@NonNull RegistrationCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        try {
            getITelephony().unregisterImsRegistrationCallback(mSubId, c.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**{@inheritDoc}*/
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterImsRegistrationCallback(
            @NonNull RegistrationManager.RegistrationCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        try {
            getITelephony().unregisterImsRegistrationCallback(mSubId, c.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**{@inheritDoc}*/
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void getRegistrationState(@NonNull @ImsRegistrationState Consumer<Integer> stateCallback,
            @NonNull @CallbackExecutor Executor executor) {
        if (stateCallback == null) {
            throw new IllegalArgumentException("Must include a non-null callback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        try {
            getITelephony().getImsMmTelRegistrationState(mSubId, new IIntegerConsumer.Stub() {
                @Override
                public void accept(int result) {
                    executor.execute(() -> stateCallback.accept(result));
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**{@inheritDoc}*/
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void getRegistrationTransportType(
            @NonNull @AccessNetworkConstants.TransportType Consumer<Integer> transportTypeCallback,
            @NonNull @CallbackExecutor Executor executor) {
        if (transportTypeCallback == null) {
            throw new IllegalArgumentException("Must include a non-null callback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        try {
            getITelephony().getImsMmTelRegistrationTransportType(mSubId,
                    new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            executor.execute(() -> transportTypeCallback.accept(result));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Registers a {@link CapabilityCallback} with the system, which will provide MmTel service
     * availability updates for the subscription specified in
     * {@link #createForSubscriptionId(int)}. The method {@link #isAvailable(int, int)}
     * can also be used to query this information at any time.
     *
     * Use {@link SubscriptionManager.OnSubscriptionsChangedListener} to listen to
     * subscription changed events and call
     * {@link #unregisterMmTelCapabilityCallback(CapabilityCallback)} to clean up.
     *
     * When the callback is registered, it will initiate the callback c to be called with the
     * current capabilities.
     *
     * @param executor The executor the callback events should be run on.
     * @param c The MmTel {@link CapabilityCallback} to be registered.
     * @see #unregisterMmTelCapabilityCallback(CapabilityCallback)
     * @throws IllegalArgumentException if the subscription associated with this callback is not
     * active (SIM is not inserted, ESIM inactive) or invalid, or a null {@link Executor} or
     * {@link CapabilityCallback} callback.
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerMmTelCapabilityCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull CapabilityCallback c) throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        c.setExecutor(executor);
        try {
            getITelephony().registerMmTelCapabilityCallback(mSubId, c.getBinder());
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new ImsException(e.getMessage(), e.errorCode);
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }  catch (IllegalStateException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Removes an existing MmTel {@link CapabilityCallback}.
     *
     * When the subscription associated with this callback is removed (SIM removed, ESIM swap,
     * etc...), this callback will automatically be removed. If this method is called for an
     * inactive subscription, it will result in a no-op.
     * @param c The MmTel {@link CapabilityCallback} to be removed.
     * @see #registerMmTelCapabilityCallback(Executor, CapabilityCallback)
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterMmTelCapabilityCallback(@NonNull CapabilityCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        try {
            getITelephony().unregisterMmTelCapabilityCallback(mSubId, c.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query the user’s setting for “Advanced Calling” or "Enhanced 4G LTE", which is used to
     * enable MmTel IMS features, depending on the carrier configuration for the current
     * subscription. If this setting is enabled, IMS voice and video telephony over IWLAN/LTE will
     * be enabled as long as the carrier has provisioned these services for the specified
     * subscription. Other IMS services (SMS/UT) are not affected by this user setting and depend on
     * carrier requirements.
     * <p>
     * Note: If the carrier configuration for advanced calling is not editable or hidden, this
     * method will always return the default value.
     *
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_EDITABLE_ENHANCED_4G_LTE_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_HIDE_ENHANCED_4G_LTE_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VOLTE_AVAILABLE_BOOL
     * @see #setAdvancedCallingSettingEnabled(boolean)
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @return true if the user's setting for advanced calling is enabled, false otherwise.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isAdvancedCallingSettingEnabled() {
        try {
            return getITelephony().isAdvancedCallingSettingEnabled(mSubId);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Modify the user’s setting for “Advanced Calling” or "Enhanced 4G LTE", which is used to
     * enable MmTel IMS features, depending on the carrier configuration for the current
     * subscription. If this setting is enabled, IMS voice and video telephony over IWLAN/LTE will
     * be enabled as long as the carrier has provisioned these services for the specified
     * subscription. Other IMS services (SMS/UT) are not affected by this user setting and depend on
     * carrier requirements.
     *
     * Modifying this value may also trigger an IMS registration or deregistration, depending on
     * whether or not the new value is enabled or disabled.
     *
     * Note: If the carrier configuration for advanced calling is not editable or hidden, this
     * method will do nothing and will instead always use the default value.
     *
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_EDITABLE_ENHANCED_4G_LTE_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_HIDE_ENHANCED_4G_LTE_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VOLTE_AVAILABLE_BOOL
     * @see #isAdvancedCallingSettingEnabled()
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setAdvancedCallingSettingEnabled(boolean isEnabled) {
        try {
            getITelephony().setAdvancedCallingSettingEnabled(mSubId, isEnabled);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query the IMS MmTel capability for a given registration technology. This does not
     * necessarily mean that we are registered and the capability is available, but rather the
     * subscription is capable of this service over IMS.
     *
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VOLTE_AVAILABLE_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VT_AVAILABLE_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_IMS_GBA_REQUIRED_BOOL
     * @see #isAvailable(int, int)
     *
     * @param imsRegTech The IMS registration technology, can be one of the following:
     *         {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE},
     *         {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN}
     * @param capability The IMS MmTel capability to query, can be one of the following:
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE},
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO,
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_UT},
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_SMS}
     * @return {@code true} if the MmTel IMS capability is capable for this subscription, false
     *         otherwise.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isCapable(@MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int imsRegTech) {
        try {
            return getITelephony().isCapable(mSubId, capability, imsRegTech);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query the availability of an IMS MmTel capability for a given registration technology. If
     * a capability is available, IMS is registered and the service is currently available over IMS.
     *
     * @see #isCapable(int, int)
     *
     * @param imsRegTech The IMS registration technology, can be one of the following:
     *         {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE},
     *         {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN}
     * @param capability The IMS MmTel capability to query, can be one of the following:
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE},
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO,
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_UT},
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_SMS}
     * @return {@code true} if the MmTel IMS capability is available for this subscription, false
     *         otherwise.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isAvailable(@MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int imsRegTech) {
        try {
            return getITelephony().isAvailable(mSubId, capability, imsRegTech);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query whether or not the requested MmTel capability is supported by the carrier on the
     * specified network transport.
     * <p>
     * This is a configuration option and does not change. The only time this may change is if a
     * new IMS configuration is loaded when there is a
     * {@link CarrierConfigManager#ACTION_CARRIER_CONFIG_CHANGED} broadcast for this subscription.
     * @param capability The capability that is being queried for support on the carrier network.
     * @param transportType The transport type of the capability to check support for.
     * @param callback A consumer containing a Boolean result specifying whether or not the
     *                 capability is supported on this carrier network for the transport specified.
     * @param executor The executor that the callback will be called with.
     * @throws ImsException if the subscription is no longer valid or the IMS service is not
     * available.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void isSupported(@MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @AccessNetworkConstants.TransportType int transportType,
            @NonNull Consumer<Boolean> callback,
            @NonNull @CallbackExecutor Executor executor) throws ImsException {
        if (callback == null) {
            throw new IllegalArgumentException("Must include a non-null Consumer.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        try {
            getITelephony().isMmTelCapabilitySupported(mSubId, new IIntegerConsumer.Stub() {
                @Override
                public void accept(int result) {
                    executor.execute(() -> callback.accept(result == 1));
                }
            }, capability, transportType);
        } catch (ServiceSpecificException sse) {
            throw new ImsException(sse.getMessage(), sse.errorCode);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * The user's setting for whether or not they have enabled the "Video Calling" setting.
     *
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @return true if the user’s “Video Calling” setting is currently enabled.
     * @see #setVtSettingEnabled(boolean)
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isVtSettingEnabled() {
        try {
            return getITelephony().isVtSettingEnabled(mSubId);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Change the user's setting for Video Telephony and enable the Video Telephony capability.
     *
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @see #isVtSettingEnabled()
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVtSettingEnabled(boolean isEnabled) {
        try {
            getITelephony().setVtSettingEnabled(mSubId, isEnabled);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * @return true if the user's setting for Voice over WiFi is enabled and false if it is not.
     *
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @see #setVoWiFiSettingEnabled(boolean)
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isVoWiFiSettingEnabled() {
        try {
            return getITelephony().isVoWiFiSettingEnabled(mSubId);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Sets the user's setting for whether or not Voice over WiFi is enabled.
     *
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @param isEnabled true if the user's setting for Voice over WiFi is enabled, false otherwise=
     * @see #isVoWiFiSettingEnabled()
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiSettingEnabled(boolean isEnabled) {
        try {
            getITelephony().setVoWiFiSettingEnabled(mSubId, isEnabled);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns the user's voice over WiFi roaming setting associated with the current subscription.
     *
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @return true if the user's setting for Voice over WiFi while roaming is enabled, false
     * if disabled.
     * @see #setVoWiFiRoamingSettingEnabled(boolean)
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isVoWiFiRoamingSettingEnabled() {
        try {
            return getITelephony().isVoWiFiRoamingSettingEnabled(mSubId);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Change the user's setting for Voice over WiFi while roaming.
     *
     * @param isEnabled true if the user's setting for Voice over WiFi while roaming is enabled,
     *     false otherwise.
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @see #isVoWiFiRoamingSettingEnabled()
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiRoamingSettingEnabled(boolean isEnabled) {
        try {
            getITelephony().setVoWiFiRoamingSettingEnabled(mSubId, isEnabled);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Overrides the Voice over WiFi capability to true for IMS, but do not persist the setting.
     * Typically used during the Voice over WiFi registration process for some carriers.
     *
     * @param isCapable true if the IMS stack should try to register for IMS over IWLAN, false
     *     otherwise.
     * @param mode the Voice over WiFi mode preference to set, which can be one of the following:
     * - {@link #WIFI_MODE_WIFI_ONLY}
     * - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     * - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @see #setVoWiFiSettingEnabled(boolean)
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiNonPersistent(boolean isCapable, int mode) {
        try {
            getITelephony().setVoWiFiNonPersistent(mSubId, isCapable, mode);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns the user's voice over WiFi Roaming mode setting associated with the device.
     *
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @return The Voice over WiFi Mode preference set by the user, which can be one of the
     * following:
     * - {@link #WIFI_MODE_WIFI_ONLY}
     * - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     * - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @see #setVoWiFiSettingEnabled(boolean)
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @WiFiCallingMode int getVoWiFiModeSetting() {
        try {
            return getITelephony().getVoWiFiModeSetting(mSubId);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the user's preference for Voice over WiFi calling mode.
     * @param mode The user's preference for the technology to register for IMS over, can be one of
     *    the following:
     * - {@link #WIFI_MODE_WIFI_ONLY}
     * - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     * - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @see #getVoWiFiModeSetting()
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiModeSetting(@WiFiCallingMode int mode) {
        try {
            getITelephony().setVoWiFiModeSetting(mSubId, mode);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the user's preference for Voice over WiFi calling mode while the device is roaming on
     * another network.
     *
     * @return The user's preference for the technology to register for IMS over when roaming on
     *     another network, can be one of the following:
     *     - {@link #WIFI_MODE_WIFI_ONLY}
     *     - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     *     - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @see #setVoWiFiRoamingSettingEnabled(boolean)
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @WiFiCallingMode int getVoWiFiRoamingModeSetting() {
        try {
            return getITelephony().getVoWiFiRoamingModeSetting(mSubId);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the user's preference for Voice over WiFi mode while the device is roaming on another
     * network.
     *
     * @param mode The user's preference for the technology to register for IMS over when roaming on
     *     another network, can be one of the following:
     *     - {@link #WIFI_MODE_WIFI_ONLY}
     *     - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     *     - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @see #getVoWiFiRoamingModeSetting()
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiRoamingModeSetting(@WiFiCallingMode int mode) {
        try {
            getITelephony().setVoWiFiRoamingModeSetting(mSubId, mode);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Sets the capability of RTT for IMS calls placed on this subscription.
     *
     * Note: This does not affect the value of
     * {@link android.provider.Settings.Secure#RTT_CALLING_MODE}, which is the global user setting
     * for RTT. That value is enabled/disabled separately by the user through the Accessibility
     * settings.
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @param isEnabled if true RTT should be enabled during calls made on this subscription.
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setRttCapabilitySetting(boolean isEnabled) {
        try {
            getITelephony().setRttCapabilitySetting(mSubId, isEnabled);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * @return true if TTY over VoLTE is supported
     *
     * @throws IllegalArgumentException if the subscription associated with this operation is not
     * active (SIM is not inserted, ESIM inactive) or invalid.
     * @see android.telecom.TelecomManager#getCurrentTtyMode
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isTtyOverVolteEnabled() {
        try {
            return getITelephony().isTtyOverVolteEnabled(mSubId);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == ImsException.CODE_ERROR_INVALID_SUBSCRIPTION) {
                // Rethrow as runtime error to keep API compatible.
                throw new IllegalArgumentException(e.getMessage());
            } else {
                throw new RuntimeException(e.getMessage());
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Get the status of the MmTel Feature registered on this subscription.
     * @param callback A callback containing an Integer describing the current state of the
     *                 MmTel feature, Which will be one of the following:
     *                 {@link ImsFeature#STATE_UNAVAILABLE},
     *                {@link ImsFeature#STATE_INITIALIZING},
     *                {@link ImsFeature#STATE_READY}. Will be called using the executor
     *                 specified when the service state has been retrieved from the IMS service.
     * @param executor The executor that will be used to call the callback.
     * @throws ImsException if the IMS service associated with this subscription is not available or
     * the IMS service is not available.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void getFeatureState(@NonNull @ImsFeature.ImsState Consumer<Integer> callback,
            @NonNull @CallbackExecutor Executor executor) throws ImsException {
        if (callback == null) {
            throw new IllegalArgumentException("Must include a non-null Consumer.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        try {
            getITelephony().getImsMmTelFeatureState(mSubId, new IIntegerConsumer.Stub() {
                @Override
                public void accept(int result) {
                    executor.execute(() -> callback.accept(result));
                }
            });
        } catch (ServiceSpecificException sse) {
            throw new ImsException(sse.getMessage(), sse.errorCode);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    private static ITelephony getITelephony() {
        ITelephony binder = ITelephony.Stub.asInterface(
                ServiceManager.getService(Context.TELEPHONY_SERVICE));
        if (binder == null) {
            throw new RuntimeException("Could not find Telephony Service.");
        }
        return binder;
    }
}
