# Native companion creation

Create `NativeCompanionCreationViewModel` and, in Activity `onCreate`,
`NativeCompanionCreationMediaCoordinator(activity, model)`. The screen is:

```kotlin
NativeCompanionCreationScreen(state, model, onBack, onCreated,
    onChooseImage = creationMedia::chooseImage)
```

Observe `model.state` and call `startObserving()` / `stopObserving()` with screen
visibility. `onCreated(id)` is delivered only after an actual creation receipt or
a committed draft read. Refresh the shared companion catalog and wait until it
contains this ID before selecting the new chat. The screen consumes the local
creation journal after that callback returns; returning asynchronously from the
callback does not imply that the shared chat ViewModel has already refreshed.

Root navigation owns the creation route and its add button. The templates root
leaves system back to NavHost. Internal pages use the shared global
`droyNavigationMotion()` and Material components; no separate animation specs,
theme, browser, credentials or model-inference channel is introduced.

Merge the coordinator's Java-callable static methods into the Host gates:

- `NativeCompanionCreationMediaCoordinator.isPickerOpen()`
- `NativeCompanionCreationMediaCoordinator.hasRuntimeWork()`

Only `PICKING` holds the human picker gate. Preparing a draft, creating,
importing and uploading hold runtime work. An unknown result waiting for the
user holds neither. The ViewModel reports changes through
`NativeRuntimeHost.backgroundWorkChanged()` and releases its own holds when
cleared. Activity recreation retains the ViewModel and pending selection.

Every image request derives its registry key from its durable operation UUID.
Recreation restores the same key. Callbacks carry that exact UUID, preventing a
late result from a discarded picker from applying to a newer draft. Registry
callbacks are explicitly unregistered when their Activity is destroyed.

Images use the fixed `MediaInputStore.nativeCompanionCreation(context)` private
namespace, its normal admission and normalization, and manifest-member
`openItem`. A stable import UUID identifies the private copy. No raw path or
ContentResolver read bypass is added. The real creation draft and AVATAR or
BACKGROUND purpose are journaled separately from the local importer DTO.

Template defaults and suggested names come from `GET /droy/companions/templates`.
Model candidates come from the existing companion catalog. Identity, persona,
model, interaction settings, avatar and background are submitted together through
one `POST /droy/companions`; the UI does not create then PATCH a partial profile.

The private journal is committed before preparing a draft and again before the
creation POST. All creations have a known persisted service draft ID. Recovery
uses GET draft state: committed yields its actual companion ID, open requires an
explicit retry of the same frozen payload, and cancelled permits explicit
editing again. Preparation receipt loss retains the same request ID and permits
an explicit preparation retry. A name conflict retains the entered name and
other edits; it never silently picks another name.

Cancelling is a draft transaction, not deletion of a companion. A committed
cancel response opens the existing companion. Uploaded draft images transfer
ownership only in the backend creation transaction. Local image results alter
only the draft until the user presses Create. No runtime service or shared media
module is modified by this feature.
