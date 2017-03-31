/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
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
package com.waz.zclient.views

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.support.v4.view.ViewPager.OnPageChangeListener
import android.support.v4.view.{PagerAdapter, ViewPager}
import android.util.AttributeSet
import android.view.View.OnLongClickListener
import android.view.ViewGroup.LayoutParams
import android.view.{View, ViewGroup}
import com.waz.api.MessageFilter
import com.waz.model.{AssetId, MessageData}
import com.waz.service.ZMessaging
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, EventStream, Signal, SourceSignal}
import com.waz.zclient.controllers.global.SelectionController
import com.waz.zclient.conversation.CollectionController
import com.waz.zclient.conversation.CollectionController.{AllContent, ContentType, Images}
import com.waz.zclient.messages.RecyclerCursor
import com.waz.zclient.messages.RecyclerCursor.RecyclerNotifier
import com.waz.zclient.messages.controllers.MessageActionsController
import com.waz.zclient.pages.main.conversationpager.CustomPagerTransformer
import com.waz.zclient.views.ImageController.WireImage
import com.waz.zclient.views.images.TouchImageView
import com.waz.zclient.{Injectable, Injector, ViewHelper}
import com.waz.ZLog.ImplicitTag._

import scala.collection.mutable
import scala.concurrent.Future

class ImageViewPager(context: Context, attrs: AttributeSet) extends ViewPager(context, attrs) with ViewHelper {
  import Threading.Implicits.Ui
  def this(context: Context) = this(context, null)

  lazy val zms = inject[Signal[ZMessaging]]
  lazy val messageActions = inject[MessageActionsController]
  lazy val collectionController = inject[CollectionController]

  val imageSwipeAdapter = new ImageSwipeAdapter(getContext)
  setAdapter(imageSwipeAdapter)

  addOnPageChangeListener(new OnPageChangeListener {
    override def onPageScrollStateChanged(state: Int): Unit = {}
    override def onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int): Unit = {}
    override def onPageSelected(position: Int): Unit = collectionController.focusedItem ! imageSwipeAdapter.getItem(position)
  })
  setPageTransformer(false, new CustomPagerTransformer (CustomPagerTransformer.SLIDE))

  val focusedItemPos = for {
    Some(msg) <- collectionController.focusedItem
    cursor <- imageSwipeAdapter.cursor
    pos <- Signal.future(cursor.positionForMessage(msg))
  } yield pos

  focusedItemPos.on(Threading.Ui) {
    pos =>
      if (pos >= 0) setCurrentItem(pos, false)
  }

}

class ImageSwipeAdapter(context: Context)(implicit injector: Injector, ev: EventContext) extends PagerAdapter with Injectable{ self =>

  private val discardedImages = mutable.Queue[SwipeImageView]()

  private val zms = inject[Signal[ZMessaging]]
  private val selectedConversation = inject[SelectionController].selectedConv

  val contentMode = Signal[ContentType](AllContent)

  val conv = for {
    zs <- zms
    convId <- selectedConversation
    conv <- Signal future zs.convsStorage.get(convId)
  } yield conv

  val notifier = new RecyclerNotifier(){
    override def notifyDataSetChanged(): Unit = self.notifyDataSetChanged()

    override def notifyItemRangeInserted(index: Int, length: Int): Unit = self.notifyDataSetChanged()

    override def notifyItemRangeChanged(index: Int, length: Int): Unit = self.notifyDataSetChanged()

    override def notifyItemRangeRemoved(pos: Int, count: Int): Unit = self.notifyDataSetChanged()
  }

  var recyclerCursor: Option[RecyclerCursor] = None
  val cursor = for {
    zs <- zms
    Some(c) <- conv
  } yield new RecyclerCursor(c.id, zs, notifier, Some(MessageFilter(Some(Images.typeFilter))))

  cursor.on(Threading.Ui) { c =>
    if (!recyclerCursor.contains(c)) {
      recyclerCursor.foreach(_.close())
      recyclerCursor = Some(c)
      notifier.notifyDataSetChanged()
    }
  }

  def getItem(position: Int): Option[MessageData] = {
    recyclerCursor.flatMap{
      case c if c.count > position => Some(c.apply(position).message)
      case _ => None
    }
  }

  override def instantiateItem(container: ViewGroup, position: Int): AnyRef = {
    val imageView = if (discardedImages.nonEmpty) discardedImages.dequeue() else new SwipeImageView(context)
    imageView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    imageView.setImageDrawable(new ColorDrawable(Color.TRANSPARENT))
    getItem(position).foreach{ messageData => imageView.setMessageData(messageData) }
    imageView.setTag(position)
    container.addView(imageView)
    imageView
  }

  override def destroyItem(container: ViewGroup, position: Int, obj: scala.Any): Unit = {
    val view = obj.asInstanceOf[SwipeImageView]
    view.resetZoom()
    discardedImages.enqueue(view)
    container.removeView(view)
  }

  override def isViewFromObject(view: View, obj: scala.Any): Boolean = {
    view.getTag.equals(obj.asInstanceOf[View].getTag)
  }

  override def getCount: Int = recyclerCursor.fold(0)(_.count)
}

class SwipeImageView(context: Context, attrs: AttributeSet, style: Int)(implicit injector: Injector, ev: EventContext) extends TouchImageView(context, attrs, style) with Injectable{
  def this(context: Context, attrs: AttributeSet)(implicit injector: Injector, ev: EventContext) = this(context, attrs, 0)
  def this(context: Context)(implicit injector: Injector, ev: EventContext) = this(context, null, 0)

  lazy val zms = inject[Signal[ZMessaging]]
  lazy val messageActions = inject[MessageActionsController]

  private val messageData: SourceSignal[MessageData] = Signal[MessageData]()
  private val onLayoutChanged = EventStream[Unit]()
  private val messageAndLikes = zms.zip(messageData).flatMap{
    case (z, md) => Signal.future(z.msgAndLikes.combineWithLikes(md))
    case _ => Signal[MessageAndLikes]()
  }
  messageAndLikes.disableAutowiring()

  messageData.on(Threading.Ui){
    md => setAsset(md.assetId)
  }
  onLayoutChanged.on(Threading.Ui){
    _ => messageData.currentValue.foreach(md => setAsset(md.assetId))
  }

  setOnLongClickListener(new OnLongClickListener {
    override def onLongClick(v: View): Boolean = {
      messageAndLikes.currentValue.foreach(messageActions.showDialog(_, fromCollection = true))
      true
    }
  })

  private def setAsset(assetId: AssetId): Unit =
    setImageDrawable(new ImageAssetDrawable(Signal(WireImage(assetId)), scaleType = ImageAssetDrawable.ScaleType.CenterInside))

  override def onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int): Unit = {
    super.onLayout(changed, left, top, right, bottom)
    onLayoutChanged ! (())
  }

  def setMessageData(messageData: MessageData): Unit = {
    this.messageData ! messageData
  }
}

