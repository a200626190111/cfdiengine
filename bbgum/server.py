from custom.profile import ProfileReader
from bbgum.frame import Action, Frame, FrameError

import multiprocessing
import threading
import socket
import os

class BbGumServerError(Exception):
    def __init__(self, message = None):
        self.message = message
    def __str__(self):
        return self.message

class BbGumServer(object):

    __HOST = ''     # Symbolic name meaning all available interfaces
    __QCON_MAX = 5  # Maximum number of queued connections

    def __init__(self, logger, port):
        self.logger = logger
        self.port = port

    def start(self, factory, forking = True):
        """start the service upon selected port"""

        def listener():
            self.logger.debug("listening")
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.bind((self.__HOST, self.port))
            self.socket.listen(self.__QCON_MAX)

        def spawner():
            print('Use Control-C to exit')
            while True:
                conn, address = self.socket.accept()
                self.logger.debug("Got connection")
                if not forking:
                    # just one connection as per current thread
                    self.conn_delegate(conn, address, factory)
                    continue
                process = multiprocessing.Process(
                    target=self.conn_delegate, args=(conn, address, factory))
                process.daemon = True
                process.start()
                self.logger.debug("Started process %r", process)

        def shutdown():
            self.logger.info("Shutting down")
            for process in multiprocessing.active_children():
                self.logger.info("Shutting down process %r", process)
                process.terminate()
                process.join()

        try:
            listener()
            spawner()
        except KeyboardInterrupt:
            print('Exiting')
        except BbGumServerError as e:
            raise
        except Exception as e:
            raise
        finally:
            shutdown()

    def conn_delegate(self, conn, addr, factory):
        '''deals with an active connection'''
        def read_socket(s):
            d = conn.recv(s)
            if d == b'':
                raise RuntimeError("socket connection broken")
        read_header = lambda : read_socket(Frame.FRAME_HEADER_LENGTH)
        read_body = lambda hs: read_socket(hs)
        mon = Monitor(self.logger, conn, factory)
        try:
            self.logger.debug("Connected %r at %r", conn, addr)
            while True:
                mon.receive(Action(read_body(Frame.decode_header(read_header()))))
        except (RuntimeError, FrameError) as e:
            self.logger.exception(e)
        except:
            self.logger.exception("Problem handling request")
        finally:
            self.logger.debug("Closing socket")
            conn.close()
