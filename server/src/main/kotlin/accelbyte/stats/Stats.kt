package accelbyte.stats

import accelbyte.AccelByteSdkProvider
import accelbyte.session.toJsonElement
import accelbyte.session.toModel
import net.accelbyte.sdk.api.chat.operations.inbox.AdminGetInboxStats
import net.accelbyte.sdk.api.chat.wrappers.Inbox
import net.accelbyte.sdk.api.platform.operations.order.GetOrderStatistics
import net.accelbyte.sdk.api.platform.wrappers.Order
import net.accelbyte.sdk.api.reporting.operations.admin_tickets.TicketStatistic
import net.accelbyte.sdk.api.reporting.wrappers.AdminTickets
import net.accelbyte.sdk.api.social.wrappers.GlobalStatistic
import net.accelbyte.sdk.api.social.wrappers.StatConfiguration
import net.accelbyte.sdk.api.social.wrappers.StatCycleConfiguration
import net.accelbyte.sdk.api.social.wrappers.UserStatistic
import net.accelbyte.sdk.api.social.wrappers.UserStatisticCycle
import structs.accelbyte.chat.InboxStatsResponse
import structs.accelbyte.platform.OrderStatistics
import structs.accelbyte.reporting.TicketStatisticResponse
import java.io.InputStream

object Stats
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace

    private val statConfigurationApi by lazy { StatConfiguration(sdk) }
    private val statCycleConfigurationApi by lazy { StatCycleConfiguration(sdk) }
    private val userStatisticApi by lazy { UserStatistic(sdk) }
    private val userStatisticCycleApi by lazy { UserStatisticCycle(sdk) }
    private val globalStatisticApi by lazy { GlobalStatistic(sdk) }
    private val chatInbox by lazy { Inbox(sdk) }
    private val orderWrapper by lazy { Order(sdk) }
    private val reportingAdmin by lazy { AdminTickets(sdk) }

    private inline fun <T> runAction(action : () -> T) : Result<T> = runCatching { action() }

    private inline fun <reified T> runModel(action : () -> Any) : Result<T> =
        runCatching { action().toJsonElement().toModel<T>() }

    private fun runBytes(action : () -> InputStream) : Result<ByteArray> =
        runAction { action().use { it.readBytes() } }

    /**
     * Executes a statistic configuration operation with result handling.
     * 
     * @param action Lambda function to execute on [StatConfiguration] wrapper
     * @return [Result] containing the operation result
     */
    fun <T> statConfiguration(action : StatConfiguration.() -> T) : Result<T> =
        runAction { statConfigurationApi.action() }

    /**
     * Executes a statistic configuration operation that returns Unit.
     * 
     * @param action Lambda function to execute on [StatConfiguration] wrapper
     * @return [Result] indicating success or failure
     */
    fun statConfigurationUnit(action : StatConfiguration.() -> Unit) : Result<Unit> =
        runAction { statConfigurationApi.action() }

    /**
     * Executes a statistic configuration export operation.
     * 
     * @param action Lambda function that returns InputStream from [StatConfiguration]
     * @return [Result] containing exported data as ByteArray
     */
    fun statConfigurationExport(action : StatConfiguration.() -> InputStream) : Result<ByteArray> =
        runBytes { statConfigurationApi.action() }

    /**
     * Executes a statistic cycle configuration operation with result handling.
     * 
     * @param action Lambda function to execute on [StatCycleConfiguration] wrapper
     * @return [Result] containing the operation result
     */
    fun <T> statCycleConfiguration(action : StatCycleConfiguration.() -> T) : Result<T> =
        runAction { statCycleConfigurationApi.action() }

    /**
     * Executes a statistic cycle configuration operation that returns Unit.
     * 
     * @param action Lambda function to execute on [StatCycleConfiguration] wrapper
     * @return [Result] indicating success or failure
     */
    fun statCycleConfigurationUnit(action : StatCycleConfiguration.() -> Unit) : Result<Unit> =
        runAction { statCycleConfigurationApi.action() }

    /**
     * Executes a statistic cycle configuration export operation.
     * 
     * @param action Lambda function that returns InputStream from [StatCycleConfiguration]
     * @return [Result] containing exported data as ByteArray
     */
    fun statCycleConfigurationExport(action : StatCycleConfiguration.() -> InputStream) : Result<ByteArray> =
        runBytes { statCycleConfigurationApi.action() }

    /**
     * Executes a user statistic operation with result handling.
     * 
     * @param action Lambda function to execute on [UserStatistic] wrapper
     * @return [Result] containing the operation result
     */
    fun <T> userStatistic(action : UserStatistic.() -> T) : Result<T> =
        runAction { userStatisticApi.action() }

    /**
     * Executes a user statistic operation that returns Unit.
     * 
     * @param action Lambda function to execute on [UserStatistic] wrapper
     * @return [Result] indicating success or failure
     */
    fun userStatisticUnit(action : UserStatistic.() -> Unit) : Result<Unit> =
        runAction { userStatisticApi.action() }

    /**
     * Executes a user statistic cycle operation with result handling.
     * 
     * @param action Lambda function to execute on [UserStatisticCycle] wrapper
     * @return [Result] containing the operation result
     */
    fun <T> userStatisticCycle(action : UserStatisticCycle.() -> T) : Result<T> =
        runAction { userStatisticCycleApi.action() }

    /**
     * Executes a user statistic cycle operation that returns Unit.
     * 
     * @param action Lambda function to execute on [UserStatisticCycle] wrapper
     * @return [Result] indicating success or failure
     */
    fun userStatisticCycleUnit(action : UserStatisticCycle.() -> Unit) : Result<Unit> =
        runAction { userStatisticCycleApi.action() }

    /**
     * Executes a global statistic operation with result handling.
     * 
     * @param action Lambda function to execute on [GlobalStatistic] wrapper
     * @return [Result] containing the operation result
     */
    fun <T> globalStatistic(action : GlobalStatistic.() -> T) : Result<T> =
        runAction { globalStatisticApi.action() }

    /**
     * Retrieves chat inbox statistics with optional message filtering.
     * 
     * @param messageIds Optional list of message IDs to filter statistics by
     * @return [Result] containing [InboxStatsResponse] with inbox statistics
     */
    fun chatInboxStats(messageIds : List<String>? = null) : Result<InboxStatsResponse> = runModel {
        val builder = AdminGetInboxStats.builder().namespace(namespace)
        messageIds?.let { builder.messageId(it) }
        chatInbox.adminGetInboxStats(builder.build())
    }

    /**
     * Retrieves order statistics for the current namespace.
     * 
     * @return [Result] containing [OrderStatistics] with order data
     */
    fun orderStatistics() : Result<OrderStatistics> = runModel {
        orderWrapper.getOrderStatistics(
            GetOrderStatistics.builder()
                .namespace(namespace)
                .build()
        )
    }

    /**
     * Retrieves ticket statistics by category with optional extension filtering.
     * 
     * @param category Primary category for ticket statistics
     * @param extensionCategory Optional extension category for additional filtering
     * @return [Result] containing [TicketStatisticResponse] with ticket statistics
     */
    fun ticketStatistics(category : String, extensionCategory : String? = null) : Result<TicketStatisticResponse> =
        runModel {
            val builder = TicketStatistic.builder()
                .namespace(namespace)
                .category(category)
            extensionCategory?.let { builder.extensionCategory(it) }
            reportingAdmin.ticketStatistic(builder.build())
        }

    /**
     * Provides direct access to the statistic configuration wrapper.
     * 
     * @return [StatConfiguration] wrapper instance
     */
    fun statConfigurationWrapper() : StatConfiguration = statConfigurationApi
    
    /**
     * Provides direct access to the statistic cycle configuration wrapper.
     * 
     * @return [StatCycleConfiguration] wrapper instance
     */
    fun statCycleConfigurationWrapper() : StatCycleConfiguration = statCycleConfigurationApi
    
    /**
     * Provides direct access to the user statistic wrapper.
     * 
     * @return [UserStatistic] wrapper instance
     */
    fun userStatisticWrapper() : UserStatistic = userStatisticApi
    
    /**
     * Provides direct access to the user statistic cycle wrapper.
     * 
     * @return [UserStatisticCycle] wrapper instance
     */
    fun userStatisticCycleWrapper() : UserStatisticCycle = userStatisticCycleApi
    
    /**
     * Provides direct access to the global statistic wrapper.
     * 
     * @return [GlobalStatistic] wrapper instance
     */
    fun globalStatisticWrapper() : GlobalStatistic = globalStatisticApi
}