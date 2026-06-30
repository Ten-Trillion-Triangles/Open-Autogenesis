package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.FileUploadRequest
import org.ttt.autogenesis.accelbyte.models.NamespaceDetailsResponse
import org.ttt.autogenesis.accelbyte.models.NamespaceListResponse
import org.ttt.autogenesis.accelbyte.models.NamespaceQueryParams
import org.ttt.autogenesis.accelbyte.models.PublisherInfoResponse
import org.ttt.autogenesis.accelbyte.models.UploadSlotResponse
import org.ttt.autogenesis.accelbyte.models.UserProfileResponse
import org.ttt.autogenesis.accelbyte.models.UserProfileQueryParams
import org.ttt.autogenesis.accelbyte.models.UserProfilesResponse
import org.ttt.autogenesis.accelbyte.modules.Basic
import org.ttt.autogenesis.accelbyte.modules.FileUploadApi
import org.ttt.autogenesis.accelbyte.modules.NamespaceApi
import org.ttt.autogenesis.accelbyte.modules.UserProfileApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Exposes the basic infrastructure APIs (namespaces, file uploads, profiles).
 * Defaults map to `Basic` namespace endpoints in the AccelByte SDK.
 *
 * @param sdk AccelByte SDK instance used to resolve the Basic module APIs.
 */
class BasicFacade(private val sdk : AccelByteSdkInstance)
{
    private val namespaceApi : NamespaceApi
        get() = Basic.NamespaceApi(sdk.rawSdk)

    private val fileApi : FileUploadApi
        get() = Basic.FileUploadApi(sdk.rawSdk)

    private val profileApi : UserProfileApi
        get() = Basic.UserProfileApi(sdk.rawSdk)

    /**
     * Retrieves all namespaces accessible to the current authenticated user session.
     * 
     * Returns namespace metadata including display names, status, and permissions.
     * Useful for populating namespace selection UI or validating access rights.
     *
     * @param includeActiveOnly When true, filters results to only active/enabled namespaces. 
     *                         When false, includes disabled and archived namespaces. Default: true.
     * @return `Promise<NamespaceListResponse>` with namespace objects and paging metadata
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if user lacks namespace access permissions
     */
    fun listNamespaces(includeActiveOnly : Boolean = true) : Promise<NamespaceListResponse> =
        listNamespaces(NamespaceQueryParams(includeActiveOnly))

    /**
     * Retrieves namespaces using typed query parameters for advanced filtering.
     *
     * @param params NamespaceQueryParams with filtering options
     * @return `Promise<NamespaceListResponse>` with namespace objects and paging metadata
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if query parameters are invalid
     */
    fun listNamespaces(params : NamespaceQueryParams) : Promise<NamespaceListResponse> =
        namespaceApi.getNamespaces(params.toJson())
            .propagateJsErrors()
            .mapJson(NamespaceListResponse::fromJson)

    /**
     * Retrieves detailed information about the current namespace context.
     * 
     * Returns configuration, settings, and metadata for the namespace associated
     * with the current session token.
     *
     * @return `Promise<NamespaceDetailsResponse>` containing namespace metadata
     *         - `namespace: string` - Namespace identifier
     *         - `displayName: string` - Human-readable name
     *         - `status: string` - Current status (ACTIVE, INACTIVE, etc.)
     *         - `createdAt: string` - ISO timestamp of creation
     *         - `updatedAt: string` - ISO timestamp of last update
     *         - `config: object` - Namespace configuration settings
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     */
    fun getNamespaceInfo() : Promise<NamespaceDetailsResponse> =
        namespaceApi.getNamespace_ByNamespace()
            .propagateJsErrors()
            .mapJson(NamespaceDetailsResponse::fromJson)

    /**
     * Retrieves information about the publisher associated with the current namespace.
     * 
     * Returns publisher metadata and configuration details.
     *
     * @return `Promise<PublisherInfoResponse>` with publisher metadata
     *         - `publisherId: string` - Publisher identifier
     *         - `publisherName: string` - Publisher display name
     *         - `namespace: string` - Associated namespace
     *         - `config: object` - Publisher configuration
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     */
    fun getPublisherInfo() : Promise<PublisherInfoResponse> =
        namespaceApi.getPublisher()
            .propagateJsErrors()
            .mapJson(PublisherInfoResponse::fromJson)

    /**
     * Creates a file upload slot scoped to a specific user for content uploads.
     * 
     * Generates a pre-signed upload URL that allows direct file uploads to AccelByte storage.
     * The uploaded file will be associated with the specified user.
     *
     * @param userId Target user ID who will own the uploaded file
     * @param fileType MIME type hint for the file (e.g., "image/png", "application/json")
     * @return `Promise<UploadSlotResponse>` with upload slot metadata
     *         - `uploadUrl: string` - Pre-signed URL for direct file upload
     *         - `fileId: string` - Unique identifier for the upload slot
     *         - `expiresAt: string` - ISO timestamp when upload URL expires
     *         - `maxFileSize: number` - Maximum allowed file size in bytes
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if userId or fileType is invalid
     */
    fun createUserUpload(userId : String, fileType : String) : Promise<UploadSlotResponse> =
        createUserUpload(userId, FileUploadRequest(fileType))

    /**
     * Creates a file upload slot using typed request parameters.
     *
     * @param userId Target user ID who will own the uploaded file
     * @param request FileUploadRequest with file type and optional metadata
     * @return `Promise<UploadSlotResponse>` with upload slot metadata
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if request parameters are invalid
     */
    fun createUserUpload(userId : String, request : FileUploadRequest) : Promise<UploadSlotResponse> =
        fileApi.createFile_ByUserId(userId, request.toJson())
            .propagateJsErrors()
            .mapJson(UploadSlotResponse::fromJson)

    /**
     * Creates a file upload slot scoped to a folder path for organized storage.
     * 
     * Generates upload URL for files that will be stored in a specific folder structure.
     * Useful for organizing content by category or type.
     *
     * @param folder Target folder path where file will be stored
     * @param fileType MIME type hint for the file
     * @return `Promise<UploadSlotResponse>` with upload slot metadata containing:
     *         - `uploadUrl: string` - Pre-signed URL for direct file upload
     *         - `fileId: string` - Unique identifier for the upload slot
     *         - `folderPath: string` - Confirmed folder path
     *         - `expiresAt: string` - ISO timestamp when upload URL expires
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if folder path or fileType is invalid
     */
    fun createFolderUpload(folder : String, fileType : String) : Promise<UploadSlotResponse> =
        createFolderUpload(folder, FileUploadRequest(fileType))

    /**
     * Creates a folder upload slot using typed request parameters.
     *
     * @param folder Target folder path where file will be stored
     * @param request FileUploadRequest with file type and optional metadata
     * @return `Promise<UploadSlotResponse>` with upload slot metadata (same structure as above)
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if request parameters are invalid
     */
    fun createFolderUpload(folder : String, request : FileUploadRequest) : Promise<UploadSlotResponse> =
        fileApi.createFile_ByFolder(folder, request.toJson())
            .propagateJsErrors()
            .mapJson(UploadSlotResponse::fromJson)

    /**
     * Retrieves the current user's profile information.
     * 
     * Returns profile data for the user associated with the current session token.
     *
     * @return `Promise<UserProfileResponse>` with the authenticated user profile
     *         - `userId: string` - Unique user identifier
     *         - `displayName: string` - User's display name
     *         - `avatarUrl: string` - Profile picture URL (if set)
     *         - `publicProfile: object` - Public profile data
     *         - `createdAt: string` - Account creation timestamp
     *         - `updatedAt: string` - Last profile update timestamp
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     */
    fun fetchMyProfiles() : Promise<UserProfileResponse> =
        profileApi.getUsersMeProfiles()
            .propagateJsErrors()
            .mapJson(UserProfileResponse::fromJson)

    /**
     * Retrieves public profile information for multiple users by their IDs.
     * 
     * Returns basic profile data that is publicly visible for the specified users.
     * Useful for displaying user information in social features.
     *
     * @param userIds List of user IDs to retrieve profiles for
     * @return `Promise<UserProfilesResponse>` with public profiles array
     *         - `data: Array<PublicProfile>` - Array of public profile objects
     *         Each PublicProfile contains: userId, displayName, avatarUrl, publicProfile
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if userIds list is empty or contains invalid IDs
     */
    fun fetchUserProfiles(userIds : List<String>) : Promise<UserProfilesResponse> =
        fetchUserProfiles(UserProfileQueryParams(userIds))

    /**
     * Retrieves public user profiles using typed query parameters.
     *
     * @param request UserProfileQueryParams with user IDs and optional filters
     * @return `Promise<UserProfilesResponse>` with public profiles array (same structure as above)
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws ValidationException if request parameters are invalid
     */
    fun fetchUserProfiles(request : UserProfileQueryParams) : Promise<UserProfilesResponse> =
        profileApi.getProfilesPublic(request.toJson())
            .propagateJsErrors()
            .mapJson(UserProfilesResponse::fromJson)
}
