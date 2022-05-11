using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Windows.Storage.Streams;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using NetMQ;
using NetMQ.Sockets;

// The Blank Page item template is documented at https://go.microsoft.com/fwlink/?LinkId=402352&clcid=0x409

namespace ZeroMQServer
{
    /// <summary>
    /// An empty page that can be used on its own or navigated to within a Frame.
    /// </summary>
    public sealed partial class MainPage : Page
    {
        private static readonly string StartMSG = "fetch";
        private static readonly string SyncMsg = "sync";
        private readonly int chunks_size = 250000;
        private Stream FileStream = null;
        private byte[] buffer;
        private string fileName = null;
        public MainPage()
        {
            this.InitializeComponent();
            buffer = new byte[chunks_size];
        }

        private async void Open_File_Click(object sender, RoutedEventArgs e)
        {
            var picker = new Windows.Storage.Pickers.FileOpenPicker();
            picker.ViewMode = Windows.Storage.Pickers.PickerViewMode.Thumbnail;
            picker.SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.Downloads;
            picker.FileTypeFilter.Add("*");
            Windows.Storage.StorageFile file = await picker.PickSingleFileAsync();
            if (file != null)
            {
                // Application now has read/write access to the picked file

                FileStream = await file.OpenStreamForReadAsync();
                FileName.Text = "Picked file: " + file.Name + " " + FileStream.Length;
                fileName = file.Name;

            }
            else
            {
                FileName.Text = "Operation cancelled.";
            }
        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            var server = new RouterSocket();
                //server.Bind("tcp://" + IPAddress.Text);
            server.Bind("tcp://192.168.31.34:5556");
            Debug.WriteLine("start server");
            bool isEnd = false;
            Task.Run(() =>
            {
                while (true)
                {
                    var fetchMessage = server.ReceiveMultipartMessage();
                    //[identity]["fetch"][chunk_size][chunk_count]
                    Debug.WriteLine("message.FrameCount={0}", fetchMessage.FrameCount);
                    string identity = fetchMessage.Pop().ConvertToString();
                    if (identity == null) break;
                    string command = fetchMessage.Pop().ConvertToString();
                    if (command.Equals(StartMSG))
                    {
                        int chunkSize = Convert.ToInt32(fetchMessage.Pop().ConvertToString());
                        int chunkCount = Convert.ToInt32(fetchMessage.Pop().ConvertToString());
                        Debug.WriteLine(" chunkSize: " + chunkSize + " " + FileStream.Length);

                        if (isEnd)
                        {
                            var dataMsg = new NetMQMessage();
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(identity)));
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes("")));
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes("end")));
                            server.SendMultipartMessage(dataMsg);
                            continue;
                        }

                        while (chunkCount > 0)
                        {
                            int result = FileStream.Read(buffer, 0, chunkSize);
                            Debug.WriteLine(result);
                            var dataMsg = new NetMQMessage();
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(identity.ToString())));
                            dataMsg.Append(new NetMQFrame(buffer));

                            if (result < chunkSize)
                            {
                                isEnd = true;
                                dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes("end")));
                                server.SendMultipartMessage(dataMsg);
                                break;
                            }
                            server.SendMultipartMessage(dataMsg);
                            chunkCount--;
                        }
                    } else if (command.Equals(SyncMsg))
                    {
                        var dataMsg = new NetMQMessage();
                        dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(identity)));
                        dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(fileName)));
                        dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(FileStream.Length.ToString())));
                        server.SendMultipartMessage(dataMsg);
                    }
                    
                }
            });

        }

    }
}
